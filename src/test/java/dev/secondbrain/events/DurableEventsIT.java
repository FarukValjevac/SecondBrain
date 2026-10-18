package dev.secondbrain.events;

import dev.secondbrain.SecondBrainApplication;

import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@Testcontainers
class DurableEventsIT {
    private static final EventEnvelope.Type ACCEPTED = new EventEnvelope.Type("ProbeAccepted", 1);
    private static final EventEnvelope.Type COMPLETED = new EventEnvelope.Type("ProbeCompleted", 1);
    private static final String TRACE = "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:0.8.7-pg18-bookworm");

    private ConfigurableApplicationContext context;
    private JdbcClient jdbc;
    private JdbcEventStore store;
    private EventPublisher publisher;
    private EventDispatcher dispatcher;
    private MutableClock clock;
    private TransactionTemplate transaction;
    private ProbeHandler handler;

    @BeforeEach
    void prepareIsolatedDatabase() {
        startContext(false);
        jdbc.sql("CREATE TABLE IF NOT EXISTS probe_domain (id uuid PRIMARY KEY)").update();
        jdbc.sql(
                        """
                CREATE TABLE IF NOT EXISTS probe_effects
                    (document_id uuid, processing_version bigint, PRIMARY KEY (document_id, processing_version))
                """)
                .update();
        jdbc.sql("TRUNCATE TABLE event_keys, probe_domain, probe_effects CASCADE").update();
    }

    @AfterEach
    void closeApplication() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void deletionDiscardsIndependentVersionSequencesAndRetainsOnlyItsOwnEvent() {
        var retained = event();
        var unrelated = event();
        var higherVersion =
                new EventEnvelope(
                        UUID.randomUUID(),
                        ACCEPTED,
                        retained.documentId(),
                        400,
                        clock.instant(),
                        retained.correlationId(),
                        null,
                        null,
                        Map.of());
        publish(retained);
        publish(higherVersion);
        publish(unrelated);

        transaction.executeWithoutResult(
                status -> {
                    Assertions.assertThat(
                                    publisher.discardOtherDocumentEvents(
                                            retained.documentId(), retained.eventId()))
                            .isEqualTo(1);
                    status.setRollbackOnly();
                });
        Assertions.assertThat(count("domain_events")).isEqualTo(3);
        transaction.executeWithoutResult(
                status ->
                        Assertions.assertThat(
                                        publisher.discardOtherDocumentEvents(
                                                retained.documentId(), retained.eventId()))
                                .isEqualTo(1));
        Assertions.assertThat(count("domain_events")).isEqualTo(2);
        Assertions.assertThat(
                        jdbc.sql("SELECT event_id FROM domain_events").query(UUID.class).list())
                .containsExactlyInAnyOrder(retained.eventId(), unrelated.eventId());
        Assertions.assertThat(count("event_keys")).isEqualTo(3);
        Assertions.assertThat(publish(higherVersion)).isEqualTo(higherVersion.eventId());
        Assertions.assertThat(count("domain_events")).isEqualTo(2);
    }

    @Test
    void publicationRequiresTheDomainTransactionAndRollsBackWithIt() {
        var event = event();
        Assertions.assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(IllegalTransactionStateException.class);
        transaction.executeWithoutResult(
                status -> {
                    jdbc.sql("INSERT INTO probe_domain VALUES (:id)")
                            .param("id", event.documentId())
                            .update();
                    publisher.publish(event);
                    status.setRollbackOnly();
                });
        Assertions.assertThat(count("probe_domain")).isZero();
        Assertions.assertThat(count("event_keys")).isZero();
        Assertions.assertThat(count("domain_events")).isZero();
        Assertions.assertThat(count("event_deliveries")).isZero();
    }

    @Test
    void attemptMetersCountOnlyCommittedFencedTransitions() {
        var metrics = context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
        var first = event();
        publish(first);
        var lease = store.claim(UUID.randomUUID()).orElseThrow();
        Assertions.assertThatThrownBy(
                        () ->
                                store.complete(
                                        lease,
                                        () -> {
                                            throw new IllegalStateException(
                                                    "sentinel-private-effect");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        Assertions.assertThat(
                        metrics.get("secondbrain.event.delivery.attempts")
                                .tag("outcome", "completed")
                                .counter()
                                .count())
                .isZero();
        Assertions.assertThat(store.complete(lease, () -> EventHandler.Outcome.SKIPPED)).isTrue();
        Assertions.assertThat(store.complete(lease, () -> EventHandler.Outcome.SKIPPED)).isFalse();
        Assertions.assertThat(
                        metrics.get("secondbrain.event.delivery.attempts")
                                .tag("outcome", "skipped")
                                .counter()
                                .count())
                .isEqualTo(1);
        publish(event());
        var retry = store.claim(UUID.randomUUID()).orElseThrow();
        Assertions.assertThat(store.fail(retry, DeliveryFailure.Code.HANDLER_TRANSIENT, true))
                .isTrue();
        Assertions.assertThat(store.fail(retry, DeliveryFailure.Code.HANDLER_TRANSIENT, true))
                .isFalse();
        Assertions.assertThat(
                        metrics.get("secondbrain.event.delivery.attempts")
                                .tag("outcome", "retry")
                                .counter()
                                .count())
                .isEqualTo(1);
        clock.advance(Duration.ofSeconds(2));
        var terminal = store.claim(UUID.randomUUID()).orElseThrow();
        Assertions.assertThat(store.fail(terminal, DeliveryFailure.Code.HANDLER_PERMANENT, false))
                .isTrue();
        Assertions.assertThat(
                        metrics.get("secondbrain.event.delivery.attempts")
                                .tag("outcome", "failed")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void repeatedLogicalPublicationKeepsOneEventAndDeliveryAndRejectsConflictingIdentity() {
        var original = event();
        Assertions.assertThat(publish(original)).isEqualTo(original.eventId());
        Assertions.assertThat(publish(original)).isEqualTo(original.eventId());
        var duplicate =
                new EventEnvelope(
                        UUID.randomUUID(),
                        original.type(),
                        original.documentId(),
                        original.processingVersion(),
                        clock.instant(),
                        UUID.randomUUID(),
                        null,
                        null,
                        original.references());
        Assertions.assertThat(publish(duplicate)).isEqualTo(original.eventId());
        Assertions.assertThat(count("event_keys")).isEqualTo(1);
        Assertions.assertThat(count("domain_events")).isEqualTo(1);
        Assertions.assertThat(count("event_deliveries")).isEqualTo(1);
        var conflicting =
                new EventEnvelope(
                        original.eventId(),
                        original.type(),
                        UUID.randomUUID(),
                        1,
                        clock.instant(),
                        original.correlationId(),
                        null,
                        null,
                        Map.of());
        Assertions.assertThatThrownBy(() -> publish(conflicting))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCommittedEventIsAutomaticallyDeliveredAfterClosingAndRecreatingTheContext() {
        var original = event();
        publish(original);
        context.close();
        startContext(true);
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                Assertions.assertThat(state(original.eventId()))
                                        .isEqualTo("COMPLETED"));
        Assertions.assertThat(count("probe_effects")).isEqualTo(1);
        Assertions.assertThat(
                        jdbc.sql(
                                        """
                        SELECT correlation_id FROM domain_events e JOIN event_keys k USING(event_id)
                        WHERE k.event_type = 'ProbeCompleted'
                        """)
                                .query(UUID.class)
                                .single())
                .isEqualTo(original.correlationId());
        Assertions.assertThat(
                        jdbc.sql(
                                        """
                        SELECT causation_id FROM domain_events e JOIN event_keys k USING(event_id)
                        WHERE k.event_type = 'ProbeCompleted'
                        """)
                                .query(UUID.class)
                                .single())
                .isEqualTo(original.eventId());
        Assertions.assertThat(
                        jdbc.sql("SELECT trace_parent FROM domain_events WHERE event_id = :id")
                                .param("id", original.eventId())
                                .query(String.class)
                                .single())
                .isEqualTo(TRACE);
    }

    @Test
    void independentTransactionsCannotClaimTheSameDeliveryConcurrently() throws Exception {
        publish(event());
        var barrier = new CyclicBarrier(2);
        var results = new ArrayList<Future<Optional<DeliveryLease>>>();
        try (var threads = Executors.newFixedThreadPool(2)) {
            for (int index = 0; index < 2; index++) {
                results.add(
                        threads.submit(
                                () -> {
                                    barrier.await(5, TimeUnit.SECONDS);
                                    return store.claim(UUID.randomUUID());
                                }));
            }
            var claimed = new ArrayList<DeliveryLease>();
            for (var result : results) {
                result.get(5, TimeUnit.SECONDS).ifPresent(claimed::add);
            }
            Assertions.assertThat(claimed).hasSize(1);
        }
        Assertions.assertThat(count("event_delivery_attempts")).isEqualTo(1);
    }

    @Test
    void renewedLeasesRecoverAfterExpiryAndStaleTokensCannotAcknowledgeOrPublish() {
        var original = event();
        publish(original);
        var old = claim();
        clock.advance(Duration.ofMillis(1500));
        Assertions.assertThat(store.renew(old)).isTrue();
        clock.advance(Duration.ofSeconds(1));
        Assertions.assertThat(store.claim(UUID.randomUUID())).isEmpty();
        Assertions.assertThat(store.renew(old)).isTrue();
        var until =
                jdbc.sql("SELECT lease_until FROM event_deliveries WHERE delivery_id = :id")
                        .param("id", old.deliveryId())
                        .query(java.time.OffsetDateTime.class)
                        .single()
                        .toInstant();
        Assertions.assertThat(until).isEqualTo(old.deadline());
        clock.advance(Duration.ofSeconds(2));
        Assertions.assertThat(store.claim(UUID.randomUUID()))
                .isEmpty(); // Recovery applies backoff.
        clock.advance(Duration.ofSeconds(1));
        var fresh = claim();
        Assertions.assertThat(fresh.token()).isNotEqualTo(old.token());
        Assertions.assertThat(fresh.attempt()).isEqualTo(2);
        var staleEffect = new AtomicBoolean();
        Assertions.assertThat(
                        store.complete(
                                old,
                                () -> {
                                    staleEffect.set(true);
                                    return EventHandler.Outcome.COMPLETED;
                                }))
                .isFalse();
        Assertions.assertThat(store.renew(old)).isFalse();
        Assertions.assertThat(store.fail(old, DeliveryFailure.Code.HANDLER_TRANSIENT, true))
                .isFalse();
        Assertions.assertThat(staleEffect).isFalse();
        dispatcher.dispatch(fresh);
        dispatcher.dispatch(
                fresh); // Duplicate dispatch may prepare again, but cannot repeat DB effects.
        Assertions.assertThat(count("probe_effects")).isEqualTo(1);
        Assertions.assertThat(count("domain_events")).isEqualTo(2);
        Assertions.assertThat(
                        jdbc.sql("SELECT outcome FROM event_delivery_attempts ORDER BY attempt")
                                .query(String.class)
                                .list())
                .containsExactly("EXPIRED", "COMPLETED");
    }

    @Test
    void expiryDuringTheFinalTransactionRollsBackEffectsAndNextEvents() {
        publish(event());
        var lease = claim();
        Assertions.assertThat(
                        store.complete(
                                lease,
                                () -> {
                                    var outcome = handler.effect(lease.event()).apply();
                                    clock.advance(Duration.ofSeconds(5));
                                    return outcome;
                                }))
                .isFalse();
        Assertions.assertThat(count("probe_effects")).isZero();
        Assertions.assertThat(count("event_keys")).isEqualTo(1);
        Assertions.assertThat(count("domain_events")).isEqualTo(1);
    }

    @Test
    void effectFailureRollsBackWritesPublicationAndAcknowledgmentBeforeRetrying() {
        var original = event();
        publish(original);
        handler.behavior.set(
                event ->
                        () -> {
                            handler.effect(event).apply();
                            throw new IllegalStateException("sentinel-private-provider-input");
                        });
        dispatcher.dispatch(claim());
        Assertions.assertThat(state(original.eventId())).isEqualTo("PENDING");
        Assertions.assertThat(count("probe_effects")).isZero();
        Assertions.assertThat(count("event_keys")).isEqualTo(1);
        handler.behavior.set(null);
        clock.advance(Duration.ofSeconds(1));
        dispatcher.dispatch(claim());
        Assertions.assertThat(state(original.eventId())).isEqualTo("COMPLETED");
        Assertions.assertThat(count("probe_effects")).isEqualTo(1);
        Assertions.assertThat(count("domain_events")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"handler", "type", "schema", "payload"})
    void oldUnsupportedOrInvalidDeliveriesFailVisiblyWithoutInfiniteRetries(String invalid) {
        var original = event();
        publish(original);
        String expected;
        switch (invalid) {
            case "handler" -> {
                jdbc.sql("UPDATE event_deliveries SET handler_id = 'removed-handler'").update();
                expected = "HANDLER_MISSING";
            }
            case "type" -> {
                jdbc.sql("UPDATE event_keys SET event_type = 'UnknownLegacyType'").update();
                expected = "EVENT_UNSUPPORTED";
            }
            case "schema" -> {
                jdbc.sql("UPDATE event_keys SET schema_version = 99").update();
                expected = "SCHEMA_UNSUPPORTED";
            }
            case "payload" -> {
                jdbc.sql(
                                "UPDATE domain_events SET reference_ids = '{\"sourceId\":\"invalid-uuid\"}'::jsonb")
                        .update();
                expected = "INVALID_EVENT";
            }
            default -> throw new IllegalArgumentException("Unknown fixture");
        }
        store.claim(UUID.randomUUID()).ifPresent(dispatcher::dispatch);
        Assertions.assertThat(state(original.eventId())).isEqualTo("FAILED");
        Assertions.assertThat(failureCode(original.eventId())).isEqualTo(expected);
        clock.advance(Duration.ofDays(1));
        Assertions.assertThat(store.claim(UUID.randomUUID())).isEmpty();
        Assertions.assertThat(count("probe_effects")).isZero();
    }

    @Test
    void transientFailuresExhaustTheConfiguredBudgetAndNeverStoreExceptionText() {
        var original = event();
        publish(original);
        handler.behavior.set(
                event -> {
                    throw new IllegalArgumentException("sentinel-private-provider-input");
                });
        for (int attempt = 1; attempt <= 3; attempt++) {
            dispatcher.dispatch(claim());
            Assertions.assertThat(store.claim(UUID.randomUUID())).isEmpty();
            clock.advance(Duration.ofSeconds(1));
        }
        Assertions.assertThat(state(original.eventId())).isEqualTo("FAILED");
        Assertions.assertThat(failureCode(original.eventId())).isEqualTo("ATTEMPTS_EXHAUSTED");
        Assertions.assertThat(count("event_delivery_attempts")).isEqualTo(3);
        Assertions.assertThat(
                        jdbc.sql("SELECT row_to_json(d)::text FROM event_deliveries d")
                                .query(String.class)
                                .single())
                .doesNotContain("sentinel-private-provider-input");
        Assertions.assertThat(
                        jdbc.sql("SELECT row_to_json(a)::text FROM event_delivery_attempts a")
                                .query(String.class)
                                .list())
                .allSatisfy(
                        row ->
                                Assertions.assertThat(row)
                                        .doesNotContain("sentinel-private-provider-input"));
    }

    @Test
    void permanentFailureStopsAfterOneAttempt() {
        var original = event();
        publish(original);
        handler.behavior.set(
                event -> {
                    throw new DeliveryFailure(new IllegalArgumentException("private-input"));
                });
        dispatcher.dispatch(claim());
        clock.advance(Duration.ofMinutes(5));
        Assertions.assertThat(state(original.eventId())).isEqualTo("FAILED");
        Assertions.assertThat(failureCode(original.eventId())).isEqualTo("HANDLER_PERMANENT");
        Assertions.assertThat(store.claim(UUID.randomUUID())).isEmpty();
        Assertions.assertThat(count("event_delivery_attempts")).isEqualTo(1);
    }

    @Test
    void databaseTimeoutRollsBackTheEffectAndDownstreamPublication() {
        var original = event();
        publish(original);
        handler.behavior.set(
                event ->
                        () -> {
                            Assertions.assertThat(
                                            jdbc.sql(
                                                            "SELECT current_setting('transaction_timeout')")
                                                    .query(String.class)
                                                    .single())
                                    .isEqualTo("1s");
                            handler.effect(event).apply();
                            jdbc.sql("SELECT 1 FROM pg_sleep(5)").query(Integer.class).single();
                            return EventHandler.Outcome.COMPLETED;
                        });
        dispatcher.dispatch(claim());
        Assertions.assertThat(state(original.eventId())).isEqualTo("PENDING");
        Assertions.assertThat(count("probe_effects")).isZero();
        Assertions.assertThat(count("event_keys")).isEqualTo(1);
        Assertions.assertThat(failureCode(original.eventId())).isEqualTo("HANDLER_TRANSIENT");
    }

    @Test
    void skippedWorkIsAcknowledgedWithoutApplyingDomainEffects() {
        var original = event();
        publish(original);
        handler.behavior.set(event -> () -> EventHandler.Outcome.SKIPPED);
        dispatcher.dispatch(claim());
        Assertions.assertThat(state(original.eventId())).isEqualTo("SKIPPED");
        Assertions.assertThat(count("probe_effects")).isZero();
    }

    @Test
    void cleanupIsBoundedAndPreservesFailuresPendingWorkUnconsumedEventsAndDeduplicationFences() {
        var first = event();
        publish(first);
        dispatcher.dispatch(claim());
        var second = event();
        publish(second);
        dispatcher.dispatch(claim());
        var failed = event();
        publish(failed);
        store.fail(claim(), DeliveryFailure.Code.HANDLER_PERMANENT, false);
        var pending = event();
        publish(pending);
        clock.advance(Duration.ofDays(31));
        Assertions.assertThat(store.cleanup()).isEqualTo(1);
        Assertions.assertThat(store.cleanup()).isEqualTo(1);
        Assertions.assertThat(store.cleanup()).isZero();
        Assertions.assertThat(state(failed.eventId())).isEqualTo("FAILED");
        Assertions.assertThat(state(pending.eventId())).isEqualTo("PENDING");
        Assertions.assertThat(count("domain_events"))
                .isEqualTo(4); // Two terminal facts plus failed/pending.
        Assertions.assertThat(count("event_keys")).isEqualTo(6);
        Assertions.assertThat(publish(first)).isEqualTo(first.eventId());
        Assertions.assertThat(count("domain_events")).isEqualTo(4);
        Assertions.assertThat(count("event_delivery_attempts"))
                .isEqualTo(1); // Unresolved failure retained.
    }

    @Test
    void workerTimeoutDoesNotFreeCapacityForHandlersThatIgnoreInterruption() throws Exception {
        var entered = new CountDownLatch(2);
        var interrupted = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        Set<UUID> firstDocuments = ConcurrentHashMap.newKeySet();
        handler.behavior.set(
                event -> {
                    int current = active.incrementAndGet();
                    maximum.accumulateAndGet(current, Math::max);
                    firstDocuments.add(event.documentId());
                    entered.countDown();
                    boolean restoreInterrupt = false;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    try {
                        while (release.getCount() != 0) {
                            long remaining = deadline - System.nanoTime();
                            if (remaining <= 0) {
                                throw new IllegalStateException("Test handler wait expired");
                            }
                            try {
                                release.await(remaining, TimeUnit.NANOSECONDS);
                            } catch (InterruptedException expected) {
                                restoreInterrupt = true;
                                interrupted.countDown();
                            }
                        }
                    } finally {
                        active.decrementAndGet();
                        if (restoreInterrupt) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return handler.effect(event);
                });
        for (int index = 0; index < 3; index++) {
            publish(event());
        }
        var worker =
                new DurableEventWorker(
                        store, dispatcher, context.getBean(EventDeliveryProperties.class), clock);
        worker.start();
        try {
            Assertions.assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var timedOutDocuments = Set.copyOf(firstDocuments);
            clock.advance(Duration.ofSeconds(5));
            Assertions.assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(
                            () -> {
                                Assertions.assertThat(
                                                jdbc.sql(
                                                                "SELECT sum(attempts) FROM event_deliveries")
                                                        .query(Integer.class)
                                                        .single())
                                        .isEqualTo(2);
                                Assertions.assertThat(
                                                jdbc.sql(
                                                                "SELECT count(*) FROM event_deliveries WHERE state = 'PENDING'")
                                                        .query(Integer.class)
                                                        .single())
                                        .isEqualTo(3);
                            });
            Assertions.assertThat(active).hasValue(2);
            Assertions.assertThat(maximum).hasValue(2);
            release.countDown();
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(
                            () -> Assertions.assertThat(count("probe_effects")).isEqualTo(1));
            for (UUID id : timedOutDocuments) {
                Assertions.assertThat(
                                jdbc.sql(
                                                "SELECT count(*) FROM probe_effects WHERE document_id = :id")
                                        .param("id", id)
                                        .query(Integer.class)
                                        .single())
                        .isZero();
            }
        } finally {
            release.countDown();
            worker.stop();
        }
    }

    @Test
    void domainFailureEffectsAndRetryBookkeepingRollBackIfLeaseExpires() {
        publish(event());
        var lease = claim();
        Assertions.assertThat(
                        store.fail(
                                lease,
                                DeliveryFailure.Code.HANDLER_TRANSIENT,
                                true,
                                () -> {
                                    handler.effect(lease.event()).apply();
                                    clock.advance(Duration.ofSeconds(5));
                                    return EventHandler.Outcome.COMPLETED;
                                }))
                .isFalse();
        Assertions.assertThat(count("probe_effects")).isZero();
        Assertions.assertThat(count("domain_events")).isEqualTo(1);
        Assertions.assertThat(state(lease.event().eventId())).isEqualTo("RUNNING");
    }

    @Test
    void committedDomainFailureEffectsShareTheFailedAttemptAndRetryTransaction() {
        publish(event());
        var lease = claim();
        Assertions.assertThat(
                        store.fail(
                                lease,
                                DeliveryFailure.Code.HANDLER_TRANSIENT,
                                true,
                                () -> handler.effect(lease.event()).apply()))
                .isTrue();
        Assertions.assertThat(count("probe_effects")).isEqualTo(1);
        Assertions.assertThat(state(lease.event().eventId())).isEqualTo("PENDING");
        Assertions.assertThat(
                        jdbc.sql("SELECT outcome FROM event_delivery_attempts")
                                .query(String.class)
                                .single())
                .isEqualTo("FAILED");
    }

    private UUID publish(EventEnvelope event) {
        return transaction.execute(status -> publisher.publish(event));
    }

    private DeliveryLease claim() {
        return store.claim(UUID.randomUUID()).orElseThrow();
    }

    private EventEnvelope event() {
        return new EventEnvelope(
                UUID.randomUUID(),
                ACCEPTED,
                UUID.randomUUID(),
                1,
                clock.instant(),
                UUID.randomUUID(),
                null,
                TRACE,
                Map.of("sourceId", UUID.randomUUID()));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private String state(UUID eventId) {
        return jdbc.sql("SELECT state FROM event_deliveries WHERE event_id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
    }

    private String failureCode(UUID eventId) {
        return jdbc.sql("SELECT failure_code FROM event_deliveries WHERE event_id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
    }

    private void startContext(boolean polling) {
        context =
                new SpringApplicationBuilder(SecondBrainApplication.class, ProbeConfiguration.class)
                        .web(WebApplicationType.NONE)
                        .run(
                                "--spring.config.import=",
                                "--secondbrain.documents.cleanup.enabled=false",
                                "--secondbrain.documents.indexing.enabled=false",
                                // Probe fixtures reset event tables; document schedulers must not
                                // race them.
                                "--secondbrain.documents.extraction.reconcile-enabled=false",
                                "--secondbrain.documents.deletion.reconcile-enabled=false",
                                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                                "--spring.datasource.username=" + POSTGRES.getUsername(),
                                "--spring.datasource.password=" + POSTGRES.getPassword(),
                                "--secondbrain.events.enabled=true",
                                "--secondbrain.events.polling-enabled=" + polling,
                                "--secondbrain.events.poll-interval=50ms",
                                "--secondbrain.events.lease-duration=2s",
                                "--secondbrain.events.execution-timeout=4s",
                                "--secondbrain.events.transaction-timeout=1s",
                                "--secondbrain.events.initial-backoff=100ms",
                                "--secondbrain.events.max-backoff=1s",
                                "--secondbrain.events.max-attempts=3",
                                "--secondbrain.events.cleanup-batch-size=1");
        jdbc = context.getBean(JdbcClient.class);
        store = context.getBean(JdbcEventStore.class);
        publisher = context.getBean(EventPublisher.class);
        dispatcher = context.getBean(EventDispatcher.class);
        clock = context.getBean(MutableClock.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        handler = context.getBean(ProbeHandler.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        @Primary
        MutableClock probeClock() {
            return new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
        }

        @Bean
        EventEnvelope.Type terminalProbeType() {
            return COMPLETED;
        }

        @Bean
        ProbeHandler probeHandler(JdbcClient jdbc, EventPublisher publisher, MutableClock clock) {
            return new ProbeHandler(jdbc, publisher, clock);
        }
    }

    static final class ProbeHandler implements EventHandler {
        private final JdbcClient jdbc;
        private final EventPublisher publisher;
        private final MutableClock clock;
        private final AtomicReference<Function<EventEnvelope, Effect>> behavior =
                new AtomicReference<>();

        ProbeHandler(JdbcClient jdbc, EventPublisher publisher, MutableClock clock) {
            this.jdbc = jdbc;
            this.publisher = publisher;
            this.clock = clock;
        }

        @Override
        public String id() {
            return "probe";
        }

        @Override
        public EventEnvelope.Type eventType() {
            return ACCEPTED;
        }

        @Override
        public Effect prepare(EventEnvelope event) {
            Assertions.assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            var replacement = behavior.get();
            return replacement == null ? effect(event) : replacement.apply(event);
        }

        Effect effect(EventEnvelope event) {
            return () -> {
                Assertions.assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .isTrue();
                int inserted =
                        jdbc.sql(
                                        """
                        INSERT INTO probe_effects VALUES (:document, :version) ON CONFLICT DO NOTHING
                        """)
                                .param("document", event.documentId())
                                .param("version", event.processingVersion())
                                .update();
                if (inserted == 1) {
                    publisher.publish(
                            new EventEnvelope(
                                    UUID.randomUUID(),
                                    COMPLETED,
                                    event.documentId(),
                                    event.processingVersion(),
                                    clock.instant(),
                                    event.correlationId(),
                                    event.eventId(),
                                    event.traceParent(),
                                    Map.of()));
                }
                return Outcome.COMPLETED;
            };
        }
    }
}
