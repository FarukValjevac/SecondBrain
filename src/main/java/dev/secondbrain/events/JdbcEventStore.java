package dev.secondbrain.events;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

final class JdbcEventStore {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final EventDeliveryProperties settings;
    private final EventRetryPolicy retry;
    private final TransactionTemplate mandatory;
    private final TransactionTemplate isolated;
    private final EventMetrics metrics;

    JdbcEventStore(
            JdbcClient jdbc,
            JsonMapper json,
            Clock clock,
            EventDeliveryProperties settings,
            EventRetryPolicy retry,
            PlatformTransactionManager transactions,
            EventMetrics metrics) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.settings = settings;
        this.retry = retry;
        this.metrics = metrics;
        this.mandatory = new TransactionTemplate(transactions);
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        this.isolated = new TransactionTemplate(transactions);
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        isolated.setTimeout(Math.toIntExact(settings.transactionTimeout().toSeconds()));
    }

    UUID publish(EventEnvelope event, List<String> recipients) {
        return Objects.requireNonNull(
                mandatory.execute(
                        status -> {
                            applyDatabaseTimeouts();
                            String references =
                                    json.writeValueAsString(new TreeMap<>(event.references()));
                            String fingerprint =
                                    fingerprint(event.type().schemaVersion() + "\n" + references);
                            var inserted =
                                    jdbc.sql(
                                                    """
                    INSERT INTO event_keys
                        (event_id, document_id, processing_version, event_type, schema_version, fingerprint)
                    VALUES (:id, :document, :version, :type, :schema, :fingerprint)
                    ON CONFLICT DO NOTHING RETURNING event_id
                    """)
                                            .param("id", event.eventId())
                                            .param("document", event.documentId())
                                            .param("version", event.processingVersion())
                                            .param("type", event.type().name())
                                            .param("schema", event.type().schemaVersion())
                                            .param("fingerprint", fingerprint)
                                            .query(UUID.class)
                                            .optional();
                            if (inserted.isEmpty()) {
                                var matches =
                                        jdbc.sql(
                                                        """
                        SELECT event_id, document_id, processing_version, event_type, fingerprint
                        FROM event_keys WHERE event_id = :id OR
                            (document_id = :document AND processing_version = :version AND event_type = :type)
                        """)
                                                .param("id", event.eventId())
                                                .param("document", event.documentId())
                                                .param("version", event.processingVersion())
                                                .param("type", event.type().name())
                                                .query(
                                                        (row, index) ->
                                                                new EventKey(
                                                                        row.getObject(
                                                                                "event_id",
                                                                                UUID.class),
                                                                        row.getObject(
                                                                                "document_id",
                                                                                UUID.class),
                                                                        row.getLong(
                                                                                "processing_version"),
                                                                        row.getString("event_type"),
                                                                        row.getString(
                                                                                "fingerprint")))
                                                .list();
                                if (matches.size() != 1
                                        || !matches.getFirst().matches(event, fingerprint)) {
                                    throw new IllegalArgumentException(
                                            "Conflicting durable event identity");
                                }
                                return matches.getFirst().eventId();
                            }
                            Instant now = clock.instant();
                            jdbc.sql(
                                            """
                    INSERT INTO domain_events
                        (event_id, occurred_at, correlation_id, causation_id, trace_parent, reference_ids, created_at)
                    VALUES (:id, :occurred, :correlation, :causation, :trace, CAST(:references AS jsonb), :now)
                    """)
                                    .param("id", event.eventId())
                                    .param("occurred", timestamp(event.occurredAt()))
                                    .param("correlation", event.correlationId())
                                    .param("causation", event.causationId())
                                    .param("trace", event.traceParent())
                                    .param("references", references)
                                    .param("now", timestamp(now))
                                    .update();
                            for (String handler : recipients) {
                                jdbc.sql(
                                                """
                        INSERT INTO event_deliveries
                            (delivery_id, event_id, handler_id, eligible_at, created_at, updated_at)
                        VALUES (:delivery, :event, :handler, :now, :now, :now)
                        """)
                                        .param("delivery", UUID.randomUUID())
                                        .param("event", event.eventId())
                                        .param("handler", handler)
                                        .param("now", timestamp(now))
                                        .update();
                            }
                            return event.eventId();
                        }));
    }

    Optional<DeliveryLease> claim(UUID owner) {
        return inTransaction(
                () -> {
                    Instant now = clock.instant();
                    var candidate =
                            jdbc.sql(
                                            """
                    SELECT delivery_id, event_id, handler_id, state, attempts FROM event_deliveries
                    WHERE (state = 'PENDING' AND eligible_at <= :now)
                       OR (state = 'RUNNING' AND lease_until <= :now)
                    ORDER BY eligible_at, delivery_id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """)
                                    .param("now", timestamp(now))
                                    .query(
                                            (row, index) ->
                                                    new Candidate(
                                                            row.getObject(
                                                                    "delivery_id", UUID.class),
                                                            row.getObject("event_id", UUID.class),
                                                            row.getString("handler_id"),
                                                            row.getString("state"),
                                                            row.getInt("attempts")))
                                    .optional();
                    if (candidate.isEmpty()) {
                        return Optional.empty();
                    }
                    var selected = candidate.get();
                    if (selected.state().equals("RUNNING")) {
                        finishAttempt(
                                selected.deliveryId(),
                                selected.attempts(),
                                "EXPIRED",
                                DeliveryFailure.Code.LEASE_EXPIRED,
                                now,
                                selected.attempts() < settings.maxAttempts()
                                        ? EventMetrics.Outcome.RETRY
                                        : EventMetrics.Outcome.FAILED);
                        reschedule(
                                selected.deliveryId(),
                                selected.attempts(),
                                true,
                                DeliveryFailure.Code.LEASE_EXPIRED,
                                now);
                        return Optional.empty();
                    }
                    if (selected.attempts() >= settings.maxAttempts()) {
                        reschedule(
                                selected.deliveryId(),
                                selected.attempts(),
                                false,
                                DeliveryFailure.Code.ATTEMPTS_EXHAUSTED,
                                now);
                        return Optional.empty();
                    }
                    UUID token = UUID.randomUUID();
                    Instant deadline = now.plus(settings.executionTimeout());
                    int attempt = selected.attempts() + 1;
                    jdbc.sql(
                                    """
                    UPDATE event_deliveries SET state = 'RUNNING', attempts = :attempt,
                        lease_owner = :owner, lease_token = :token, lease_until = :until,
                        deadline_at = :deadline, updated_at = :now, failure_code = NULL
                    WHERE delivery_id = :id
                    """)
                            .param("attempt", attempt)
                            .param("owner", owner)
                            .param("token", token)
                            .param("until", timestamp(now.plus(settings.leaseDuration())))
                            .param("deadline", timestamp(deadline))
                            .param("now", timestamp(now))
                            .param("id", selected.deliveryId())
                            .update();
                    jdbc.sql(
                                    """
                    INSERT INTO event_delivery_attempts
                        (delivery_id, attempt, lease_token, started_at, outcome)
                    VALUES (:id, :attempt, :token, :now, 'RUNNING')
                    """)
                            .param("id", selected.deliveryId())
                            .param("attempt", attempt)
                            .param("token", token)
                            .param("now", timestamp(now))
                            .update();
                    EventEnvelope event;
                    try {
                        event =
                                jdbc.sql(
                                                """
                    SELECT k.*, e.occurred_at, e.correlation_id, e.causation_id, e.trace_parent, e.reference_ids
                    FROM domain_events e JOIN event_keys k USING (event_id) WHERE e.event_id = :id
                    """)
                                        .param("id", selected.eventId())
                                        .query(this::readEvent)
                                        .single();
                    } catch (IllegalArgumentException | JacksonException invalid) {
                        finishAttempt(
                                selected.deliveryId(),
                                attempt,
                                "FAILED",
                                DeliveryFailure.Code.INVALID_EVENT,
                                now,
                                EventMetrics.Outcome.FAILED);
                        reschedule(
                                selected.deliveryId(),
                                attempt,
                                false,
                                DeliveryFailure.Code.INVALID_EVENT,
                                now);
                        return Optional.empty();
                    }
                    return Optional.of(
                            new DeliveryLease(
                                    selected.deliveryId(),
                                    selected.handler(),
                                    owner,
                                    token,
                                    attempt,
                                    deadline,
                                    event));
                });
    }

    boolean renew(DeliveryLease lease) {
        return inTransaction(
                () -> {
                    Instant now = clock.instant();
                    return jdbc.sql(
                                            """
                    UPDATE event_deliveries SET lease_until = LEAST(:until, deadline_at), updated_at = :now
                    WHERE delivery_id = :id AND state = 'RUNNING' AND lease_token = :token
                        AND lease_owner = :owner AND lease_until > :now AND deadline_at > :now
                    """)
                                    .param("until", timestamp(now.plus(settings.leaseDuration())))
                                    .param("now", timestamp(now))
                                    .param("id", lease.deliveryId())
                                    .param("token", lease.token())
                                    .param("owner", lease.owner())
                                    .update()
                            == 1;
                });
    }

    boolean complete(DeliveryLease lease, EventHandler.Effect effect) {
        try {
            return inTransaction(
                    () -> {
                        Instant now = clock.instant();
                        if (!lockToken(lease, true, now)) {
                            return false;
                        }
                        var outcome = Objects.requireNonNull(effect.apply(), "Handler outcome");
                        Instant finished = clock.instant();
                        int changed =
                                jdbc.sql(
                                                """
                        UPDATE event_deliveries SET state = :outcome, lease_owner = NULL,
                            lease_token = NULL, lease_until = NULL, deadline_at = NULL, updated_at = :now
                        WHERE delivery_id = :id AND state = 'RUNNING' AND lease_token = :token
                            AND lease_until > :now AND deadline_at > :now
                        """)
                                        .param("outcome", outcome.name())
                                        .param("now", timestamp(finished))
                                        .param("id", lease.deliveryId())
                                        .param("token", lease.token())
                                        .update();
                        if (changed != 1) {
                            // The effect and any downstream publications must roll back on lease
                            // loss.
                            throw new LostLease();
                        }
                        finishAttempt(
                                lease.deliveryId(),
                                lease.attempt(),
                                outcome.name(),
                                null,
                                finished,
                                outcome == EventHandler.Outcome.SKIPPED
                                        ? EventMetrics.Outcome.SKIPPED
                                        : EventMetrics.Outcome.COMPLETED);
                        return true;
                    });
        } catch (LostLease lost) {
            return false;
        }
    }

    boolean fail(DeliveryLease lease, DeliveryFailure.Code code, boolean retryable) {
        return inTransaction(
                () -> {
                    Instant now = clock.instant();
                    if (!lockToken(lease, false, now)) {
                        return false;
                    }
                    finishAttempt(
                            lease.deliveryId(),
                            lease.attempt(),
                            "FAILED",
                            code,
                            now,
                            retryable && lease.attempt() < settings.maxAttempts()
                                    ? EventMetrics.Outcome.RETRY
                                    : EventMetrics.Outcome.FAILED);
                    reschedule(lease.deliveryId(), lease.attempt(), retryable, code, now);
                    return true;
                });
    }

    /** Domain failure effects and retry bookkeeping share an unexpired, fenced transaction. */
    boolean fail(
            DeliveryLease lease,
            DeliveryFailure.Code code,
            boolean retryable,
            EventHandler.Effect effect) {
        try {
            return inTransaction(
                    () -> {
                        if (!lockToken(lease, true, clock.instant())) return false;
                        Objects.requireNonNull(effect.apply(), "Failure outcome");
                        var now = clock.instant();
                        if (!lockToken(lease, true, now)) throw new LostLease();
                        finishAttempt(
                                lease.deliveryId(),
                                lease.attempt(),
                                "FAILED",
                                code,
                                now,
                                retryable && lease.attempt() < settings.maxAttempts()
                                        ? EventMetrics.Outcome.RETRY
                                        : EventMetrics.Outcome.FAILED);
                        reschedule(lease.deliveryId(), lease.attempt(), retryable, code, now);
                        return true;
                    });
        } catch (LostLease lost) {
            return false;
        }
    }

    int scheduleMissing(EventEnvelope.Type type, String handler, int limit) {
        if (limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Invalid scheduling batch");
        return inTransaction(
                () -> {
                    var ids =
                            jdbc.sql(
                                            """
                    SELECT e.event_id FROM domain_events e JOIN event_keys k USING (event_id)
                    WHERE k.event_type = :type AND k.schema_version = :schema
                      AND NOT EXISTS (SELECT 1 FROM event_deliveries d
                                      WHERE d.event_id = e.event_id AND d.handler_id = :handler)
                    ORDER BY e.created_at, e.event_id LIMIT :limit FOR UPDATE OF e SKIP LOCKED
                    """)
                                    .param("type", type.name())
                                    .param("schema", type.schemaVersion())
                                    .param("handler", handler)
                                    .param("limit", limit)
                                    .query(UUID.class)
                                    .list();
                    var now = timestamp(clock.instant());
                    for (var id : ids) {
                        jdbc.sql(
                                        """
                        INSERT INTO event_deliveries
                            (delivery_id, event_id, handler_id, eligible_at, created_at, updated_at)
                        VALUES (:id, :event, :handler, :now, :now, :now)
                        ON CONFLICT (event_id, handler_id) DO NOTHING
                        """)
                                .param("id", UUID.randomUUID())
                                .param("event", id)
                                .param("handler", handler)
                                .param("now", now)
                                .update();
                    }
                    return ids.size();
                });
    }

    int discardSuperseded(UUID documentId, long processingVersion) {
        Objects.requireNonNull(documentId, "documentId");
        if (processingVersion < 1) throw new IllegalArgumentException("Version must be positive");
        return Objects.requireNonNull(
                mandatory.execute(
                        status -> {
                            applyDatabaseTimeouts();
                            return jdbc.sql(
                                            """
                    DELETE FROM domain_events e USING event_keys k
                    WHERE e.event_id = k.event_id AND k.document_id = :document
                        AND k.processing_version < :version
                    """)
                                    .param("document", documentId)
                                    .param("version", processingVersion)
                                    .update();
                        }));
    }

    int cleanup() {
        return inTransaction(
                () -> {
                    Instant cutoff = clock.instant().minus(settings.completedRetention());
                    var ids =
                            jdbc.sql(
                                            """
                    SELECT e.event_id FROM domain_events e
                    WHERE EXISTS (SELECT 1 FROM event_deliveries d WHERE d.event_id = e.event_id)
                      AND NOT EXISTS (SELECT 1 FROM event_deliveries d WHERE d.event_id = e.event_id
                          AND (d.state NOT IN ('COMPLETED', 'SKIPPED') OR d.updated_at > :cutoff))
                    ORDER BY e.created_at, e.event_id LIMIT :batch FOR UPDATE OF e SKIP LOCKED
                    """)
                                    .param("cutoff", timestamp(cutoff))
                                    .param("batch", settings.cleanupBatchSize())
                                    .query(UUID.class)
                                    .list();
                    for (UUID id : ids) {
                        jdbc.sql("DELETE FROM domain_events WHERE event_id = :id")
                                .param("id", id)
                                .update();
                    }
                    return ids.size();
                });
    }

    private boolean lockToken(DeliveryLease lease, boolean requireUnexpired, Instant now) {
        return jdbc.sql(
                        """
                SELECT 1 FROM event_deliveries WHERE delivery_id = :id AND state = 'RUNNING'
                    AND lease_token = :token AND lease_owner = :owner
                    AND (:allowExpired OR (lease_until > :now AND deadline_at > :now))
                FOR UPDATE
                """)
                .param("id", lease.deliveryId())
                .param("token", lease.token())
                .param("owner", lease.owner())
                .param("allowExpired", !requireUnexpired)
                .param("now", timestamp(now))
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    private void reschedule(
            UUID id, int attempts, boolean retryable, DeliveryFailure.Code code, Instant now) {
        boolean exhausted = attempts >= settings.maxAttempts();
        boolean retrying = retryable && !exhausted;
        jdbc.sql(
                        """
                UPDATE event_deliveries SET state = :state, eligible_at = :eligible, failure_code = :code,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL, deadline_at = NULL,
                    updated_at = :now WHERE delivery_id = :id
                """)
                .param("state", retrying ? "PENDING" : "FAILED")
                .param("eligible", timestamp(retrying ? retry.nextAttemptAt(attempts) : now))
                .param(
                        "code",
                        exhausted && retryable
                                ? DeliveryFailure.Code.ATTEMPTS_EXHAUSTED.name()
                                : code.name())
                .param("now", timestamp(now))
                .param("id", id)
                .update();
    }

    private void finishAttempt(
            UUID id,
            int attempt,
            String outcome,
            DeliveryFailure.Code code,
            Instant now,
            EventMetrics.Outcome metricOutcome) {
        var started =
                jdbc.sql(
                                """
                UPDATE event_delivery_attempts SET outcome = :outcome, finished_at = :now, failure_code = :code
                WHERE delivery_id = :id AND attempt = :attempt AND outcome = 'RUNNING'
                RETURNING started_at
                """)
                        .param("outcome", outcome)
                        .param("now", timestamp(now))
                        .param("code", code == null ? null : code.name())
                        .param("id", id)
                        .param("attempt", attempt)
                        .query(Timestamp.class)
                        .optional();
        started.ifPresent(
                time ->
                        TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                    @Override
                                    public void afterCommit() {
                                        metrics.record(
                                                metricOutcome,
                                                Duration.between(time.toInstant(), now));
                                    }
                                }));
    }

    Backlog backlog() {
        return inTransaction(
                () ->
                        jdbc.sql(
                                        """
                SELECT count(*) FILTER (WHERE (state = 'PENDING' AND eligible_at <= :now)
                           OR (state = 'RUNNING' AND lease_until <= :now)) AS ready,
                       count(*) FILTER (WHERE state = 'PENDING' AND eligible_at > :now) AS delayed,
                       count(*) FILTER (WHERE state = 'PENDING' AND attempts > 0) AS retrying,
                       count(*) FILTER (WHERE state = 'RUNNING' AND lease_until > :now) AS running,
                       count(*) FILTER (WHERE state = 'FAILED') AS failed,
                       GREATEST(0, COALESCE(EXTRACT(EPOCH FROM (CAST(:now AS timestamptz) -
                           min(CASE WHEN state = 'RUNNING' THEN lease_until ELSE eligible_at END)
                               FILTER (WHERE (state = 'PENDING' AND eligible_at <= :now)
                                   OR (state = 'RUNNING' AND lease_until <= :now)))), 0)) AS age
                FROM event_deliveries
                """)
                                .param("now", timestamp(clock.instant()))
                                .query(
                                        (row, index) ->
                                                new Backlog(
                                                        row.getLong("ready"),
                                                        row.getLong("delayed"),
                                                        row.getLong("retrying"),
                                                        row.getLong("running"),
                                                        row.getLong("failed"),
                                                        row.getDouble("age")))
                                .single());
    }

    record Backlog(
            long ready, long delayed, long retrying, long running, long failed, double age) {}

    private EventEnvelope readEvent(ResultSet row, int index) throws SQLException {
        var type =
                new EventEnvelope.Type(row.getString("event_type"), row.getInt("schema_version"));
        Map<String, UUID> references =
                json.readValue(row.getString("reference_ids"), new TypeReference<>() {});
        return new EventEnvelope(
                row.getObject("event_id", UUID.class),
                type,
                row.getObject("document_id", UUID.class),
                row.getLong("processing_version"),
                row.getTimestamp("occurred_at").toInstant(),
                row.getObject("correlation_id", UUID.class),
                row.getObject("causation_id", UUID.class),
                row.getString("trace_parent"),
                references);
    }

    private <T> T inTransaction(Supplier<T> operation) {
        return Objects.requireNonNull(
                isolated.execute(
                        status -> {
                            applyDatabaseTimeouts();
                            return operation.get();
                        }));
    }

    private void applyDatabaseTimeouts() {
        // LOCAL limits apply to the current transaction, including the publishing caller's work.
        // PostgreSQL can roll back a stalled transaction even if Java code ignores interruption.
        for (String name : List.of("statement_timeout", "transaction_timeout", "lock_timeout")) {
            long limit =
                    name.equals("lock_timeout") ? 1000 : settings.transactionTimeout().toMillis();
            jdbc.sql("SELECT set_config(:name, :value, true)")
                    .param("name", name)
                    .param("value", limit + "ms")
                    .query(String.class)
                    .single();
        }
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required SHA-256 digest unavailable", impossible);
        }
    }

    private record Candidate(
            UUID deliveryId, UUID eventId, String handler, String state, int attempts) {}

    private record EventKey(
            UUID eventId, UUID documentId, long version, String type, String fingerprint) {
        boolean matches(EventEnvelope event, String expectedFingerprint) {
            return documentId.equals(event.documentId())
                    && version == event.processingVersion()
                    && type.equals(event.type().name())
                    && fingerprint.equals(expectedFingerprint);
        }
    }

    private static final class LostLease extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
