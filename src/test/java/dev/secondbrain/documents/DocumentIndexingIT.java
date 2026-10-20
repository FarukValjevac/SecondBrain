package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;
import dev.secondbrain.events.EventPublisher;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real transactions/deliveries; the controlled stage deliberately creates no product passages. */
@Testcontainers
class DocumentIndexingIT {
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00.123456Z");
    private static final EventEnvelope.Type PROBED =
            new EventEnvelope.Type("IndexingProbeCompleted", 1);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir private Path storage;

    @AfterEach
    void clearOnlyTheIsolatedDatabaseAfterAllContextsHaveClosed() {
        JdbcClient.create(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()))
                .sql("TRUNCATE documents, event_keys, indexing_probe CASCADE")
                .update();
    }

    @Test
    void explicitRequestsRemainIdempotentAcrossRestartAndWaitForARealConsumer() {
        UUID id;
        UUID eventId;
        try (var context = start(false, false)) {
            id = fixture(context, 2);
            var service = context.getBean(DocumentIndexingService.class);
            var first = service.request(id, 7, UUID.randomUUID()).orElseThrow();
            eventId = first.eventId();
            assertThat(first.extractionVersion()).isEqualTo(2);
            assertThat(service.request(id, 7, UUID.randomUUID())).contains(first);
            assertThat(service.request(id, 6, UUID.randomUUID())).isEmpty();
            assertThat(count(context, "event_keys", id)).isEqualTo(1);
            assertThat(count(context, "event_deliveries", id)).isZero();
            assertThat(context.getBeansOfType(DocumentPassageHandler.class)).isEmpty();
        }
        try (var restarted = start(false, false)) {
            var service = restarted.getBean(DocumentIndexingService.class);
            assertThat(service.request(id, 7, UUID.randomUUID()).orElseThrow().eventId())
                    .isEqualTo(eventId);
            assertThat(service.requestNext(id, UUID.randomUUID()).orElseThrow().indexingVersion())
                    .isEqualTo(8);
            assertThat(service.request(id, 7, UUID.randomUUID())).isEmpty();
            assertThat(count(restarted, "document_index_versions", id)).isEqualTo(2);
            assertThat(count(restarted, "event_keys", id)).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void independentConcurrentRequestsSerializeWithoutDuplicateGenerations(boolean sameVersion)
            throws Exception {
        try (var first = start(false, false);
                var second = start(false, false);
                var workers = Executors.newFixedThreadPool(2)) {
            var id = fixture(first, 1);
            var barrier = new CyclicBarrier(2);
            var calls =
                    List.<java.util.concurrent.Callable<DocumentIndexingService.Receipt>>of(
                            () -> requestAfterBarrier(first, id, sameVersion, barrier),
                            () -> requestAfterBarrier(second, id, sameVersion, barrier));
            var results = workers.invokeAll(calls, 15, TimeUnit.SECONDS);
            var a = results.get(0).get(1, TimeUnit.SECONDS);
            var b = results.get(1).get(1, TimeUnit.SECONDS);
            if (sameVersion) {
                assertThat(a).isEqualTo(b);
                assertThat(count(first, "event_keys", id)).isEqualTo(1);
            } else {
                assertThat(List.of(a.indexingVersion(), b.indexingVersion()))
                        .containsExactlyInAnyOrder(1L, 2L);
                assertThat(count(first, "event_keys", id)).isEqualTo(2);
            }
        }
    }

    @Test
    void callerRollbackRemovesMetadataAndEventsAndDoesNotReportSuccess() {
        try (var context = start(false, false)) {
            var id = fixture(context, 1);
            transaction(context)
                    .executeWithoutResult(
                            status -> {
                                context.getBean(DocumentIndexingService.class)
                                        .request(id, 9, UUID.randomUUID())
                                        .orElseThrow();
                                assertThat(count(context, "event_keys", id)).isEqualTo(1);
                                status.setRollbackOnly();
                            });
            assertThat(count(context, "document_index_versions", id)).isZero();
            assertThat(count(context, "event_keys", id)).isZero();
            assertThat(context.getBean(JdbcIndexingRepository.class).findEligibleDesired(id))
                    .isEmpty();
            var meters = context.getBean(MeterRegistry.class);
            assertThat(
                            meters.find("secondbrain.document.indexing.requests")
                                    .tag("outcome", "requested")
                                    .counter())
                    .isNull();
            assertThat(
                            meters.get("secondbrain.document.indexing.requests")
                                    .tag("outcome", "unavailable")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    void publicationAndDeferredCommitFailuresRollBackTheSelectedVersionAndCanRetry(Fault fault) {
        try (var context = start(false, false)) {
            var id = fixture(context, 1);
            var service = context.getBean(DocumentIndexingService.class);
            service.requestNext(id, UUID.randomUUID()).orElseThrow();
            var jdbc = jdbc(context);
            if (fault == Fault.PUBLICATION) {
                jdbc.sql(
                                "ALTER TABLE domain_events ADD CONSTRAINT indexing_test_failure CHECK (false) NOT VALID")
                        .update();
            } else {
                jdbc.sql("CREATE TABLE indexing_allowed_versions (version bigint PRIMARY KEY)")
                        .update();
                jdbc.sql("INSERT INTO indexing_allowed_versions VALUES (1)").update();
                jdbc.sql(
                                "ALTER TABLE document_index_versions ADD CONSTRAINT indexing_test_failure FOREIGN KEY (indexing_version) REFERENCES indexing_allowed_versions(version) DEFERRABLE INITIALLY DEFERRED")
                        .update();
            }
            try {
                assertThatThrownBy(() -> service.requestNext(id, UUID.randomUUID()))
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertThat(count(context, "document_index_versions", id)).isEqualTo(1);
                assertThat(count(context, "event_keys", id)).isEqualTo(1);
                assertThat(
                                context.getBean(JdbcIndexingRepository.class)
                                        .findEligibleDesired(id)
                                        .orElseThrow()
                                        .number())
                        .isEqualTo(1);
            } finally {
                jdbc.sql(
                                fault == Fault.PUBLICATION
                                        ? "ALTER TABLE domain_events DROP CONSTRAINT indexing_test_failure"
                                        : "ALTER TABLE document_index_versions DROP CONSTRAINT indexing_test_failure")
                        .update();
                if (fault == Fault.COMMIT)
                    jdbc.sql("DROP TABLE indexing_allowed_versions").update();
            }
            assertThat(service.requestNext(id, UUID.randomUUID()).orElseThrow().indexingVersion())
                    .isEqualTo(2);
        }
    }

    @Test
    void enrollmentIsBoundedRepairsSelectedMetadataAndNeedsNoHistoricalEvents() {
        UUID first;
        UUID second;
        try (var context = start(false, false)) {
            first = fixture(context, 1);
            second = fixture(context, 1);
            var hidden = fixture(context, 1);
            jdbc(context)
                    .sql("UPDATE documents SET deleted_at = updated_at WHERE document_id = :id")
                    .param("id", hidden)
                    .update();
            var missingText = fixture(context, 1);
            jdbc(context)
                    .sql("DELETE FROM document_extractions WHERE document_id = :id")
                    .param("id", missingText)
                    .update();
            transaction(context)
                    .executeWithoutResult(
                            status -> {
                                jdbc(context)
                                        .sql(
                                                "INSERT INTO document_index_versions(document_id, indexing_version, extraction_version, created_at) VALUES (:id, 7, 1, :at)")
                                        .param("id", second)
                                        .param("at", Timestamp.from(CREATED))
                                        .update();
                                jdbc(context)
                                        .sql(
                                                "UPDATE documents SET desired_indexing_version = 7 WHERE document_id = :id")
                                        .param("id", second)
                                        .update();
                            });
            assertThat(context.getBean(JdbcIndexingRepository.class).findUnenrolled(1)).hasSize(1);
            reconcile(context);
            reconcile(context);
            assertThat(count(context, "event_keys", first)).isEqualTo(1);
            assertThat(count(context, "event_keys", second)).isEqualTo(1);
            assertThat(
                            context.getBean(JdbcIndexingRepository.class)
                                    .findEligibleDesired(second)
                                    .orElseThrow()
                                    .number())
                    .isEqualTo(7);
            assertThat(count(context, "event_keys", hidden)).isZero();
            assertThat(count(context, "event_keys", missingText)).isZero();
            assertThat(
                            context.getBean(DocumentIndexingService.class)
                                    .requestNext(hidden, UUID.randomUUID()))
                    .isEmpty();
            assertThat(
                            context.getBean(DocumentIndexingService.class)
                                    .requestNext(missingText, UUID.randomUUID()))
                    .isEmpty();
            assertThat(context.getBean(JdbcIndexingRepository.class).findUnenrolled(100)).isEmpty();
        }
        try (var restarted = start(false, false)) {
            reconcile(restarted);
            assertThat(count(restarted, "event_keys", first)).isEqualTo(1);
            assertThat(count(restarted, "event_keys", second)).isEqualTo(1);
        }
    }

    @Test
    void startupAutomaticallyEnrollsExistingTextAndKeepsItQueuedAcrossRestart() {
        UUID id;
        try (var context = start(false, false)) {
            id = fixture(context, 1);
        }
        try (var restarted = start(false, false, true)) {
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                            () -> assertThat(count(restarted, "event_keys", id)).isEqualTo(1));
        }
        try (var again = start(false, false, true)) {
            reconcile(again);
            assertThat(count(again, "event_keys", id)).isEqualTo(1);
            assertThat(count(again, "event_deliveries", id)).isZero();
        }
    }

    @Test
    void newlyExtractedTextKeepsItsCorrelationAndCausationThroughThePassageStage() {
        UUID id;
        UUID extracted;
        var correlation = UUID.randomUUID();
        try (var context = start(true, false)) {
            id = fixture(context, 2);
            extracted = publishExtraction(context, id, 2, correlation);
            // Reconciliation must not race the pending extraction handoff and lose its context.
            reconcile(context);
            assertThat(count(context, "document_index_versions", id)).isZero();
        }
        try (var restarted = start(true, true)) {
            awaitCompleted(restarted, id);
            var request =
                    jdbc(restarted)
                            .sql(
                                    "SELECT e.event_id, e.causation_id, e.correlation_id, k.processing_version, k.schema_version FROM domain_events e JOIN event_keys k USING (event_id) WHERE k.document_id = :id AND k.event_type = 'ReindexRequested'")
                            .param("id", id)
                            .query(
                                    (row, n) -> {
                                        assertThat(row.getObject("causation_id", UUID.class))
                                                .isEqualTo(extracted);
                                        assertThat(row.getObject("correlation_id", UUID.class))
                                                .isEqualTo(correlation);
                                        assertThat(row.getLong("processing_version")).isEqualTo(1);
                                        assertThat(row.getInt("schema_version")).isEqualTo(1);
                                        return row.getObject("event_id", UUID.class);
                                    })
                            .single();
            assertThat(
                            jdbc(restarted)
                                    .sql(
                                            "SELECT e.causation_id FROM domain_events e JOIN event_keys k USING (event_id) WHERE k.document_id = :id AND k.event_type = 'IndexingProbeCompleted'")
                                    .param("id", id)
                                    .query(UUID.class)
                                    .single())
                    .isEqualTo(request);
            assertThat(
                            jdbc(restarted)
                                    .sql(
                                            "SELECT count(*) FROM event_deliveries WHERE state = 'COMPLETED'")
                                    .query(Long.class)
                                    .single())
                    .isEqualTo(2);
        }
    }

    @Test
    void introducingTheConsumerSchedulesOneRetainedRequestAndCompletesAfterRestart() {
        UUID id;
        try (var context = start(false, false)) {
            id = fixture(context, 1);
            context.getBean(DocumentIndexingService.class)
                    .request(id, 7, UUID.randomUUID())
                    .orElseThrow();
        }
        try (var consumer = start(true, false)) {
            reconcile(consumer);
            reconcile(consumer);
            assertThat(count(consumer, "event_deliveries", id)).isEqualTo(1);
        }
        try (var restarted = start(true, true)) {
            awaitCompleted(restarted, id);
            assertThat(count(restarted, "indexing_probe", id)).isEqualTo(1);
            reconcile(restarted);
            assertThat(count(restarted, "event_deliveries", id)).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Stale.class)
    void aPreparedStageCannotWriteAfterReindexSourceChangeOrDeletion(Stale change)
            throws Exception {
        try (var context = start(true, false)) {
            var id = fixture(context, 1);
            var receipt =
                    context.getBean(DocumentIndexingService.class)
                            .requestNext(id, UUID.randomUUID())
                            .orElseThrow();
            var event =
                    new EventEnvelope(
                            receipt.eventId(),
                            ReindexRequestedEvent.TYPE,
                            id,
                            1,
                            CREATED,
                            UUID.randomUUID(),
                            null,
                            null,
                            Map.of());
            var prepared = context.getBean(DocumentPassageHandler.class).prepare(event);
            switch (change) {
                case REINDEX ->
                        context.getBean(DocumentIndexingService.class)
                                .requestNext(id, UUID.randomUUID())
                                .orElseThrow();
                case SOURCE ->
                        jdbc(context)
                                .sql(
                                        "UPDATE documents SET processing_version = 2 WHERE document_id = :id")
                                .param("id", id)
                                .update();
                case DELETE ->
                        context.getBean(DocumentDeletionService.class)
                                .request(id, UUID.randomUUID());
            }
            assertThat(
                            transaction(context)
                                    .<EventHandler.Outcome>execute(status -> prepared.apply()))
                    .isEqualTo(EventHandler.Outcome.SKIPPED);
            assertThat(count(context, "indexing_probe", id)).isZero();
        }
    }

    @Test
    void delayedExtractionSkipsRatherThanIndexingANewerSource() {
        try (var context = start(false, false)) {
            var id = fixture(context, 2);
            var delayed =
                    new EventEnvelope(
                            UUID.randomUUID(),
                            TextExtractedEvent.TYPE,
                            id,
                            1,
                            CREATED,
                            UUID.randomUUID(),
                            null,
                            null,
                            Map.of());
            assertThat(
                            transaction(context)
                                    .<Optional<DocumentIndexingService.Receipt>>execute(
                                            status ->
                                                    context.getBean(DocumentIndexingService.class)
                                                            .enroll(delayed)))
                    .isEmpty();
            assertThat(count(context, "event_keys", id)).isZero();
        }
    }

    @Test
    void failedStageRollsBackEffectsDownstreamEventAndAcknowledgmentThenRetries() {
        try (var context = start(true, true)) {
            var id = fixture(context, 1);
            var probe = context.getBean(Probe.class);
            probe.fail.set(true);
            context.getBean(DocumentIndexingService.class)
                    .requestNext(id, UUID.randomUUID())
                    .orElseThrow();
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                            () -> {
                                assertThat(
                                                jdbc(context)
                                                        .sql(
                                                                "SELECT state FROM event_deliveries WHERE attempts = 1")
                                                        .query(String.class)
                                                        .optional())
                                        .contains("PENDING");
                                assertThat(count(context, "indexing_probe", id)).isZero();
                                assertThat(
                                                jdbc(context)
                                                        .sql(
                                                                "SELECT count(*) FROM event_keys WHERE event_type = 'IndexingProbeCompleted'")
                                                        .query(Long.class)
                                                        .single())
                                        .isZero();
                            });
            probe.fail.set(false);
            jdbc(context)
                    .sql(
                            "UPDATE event_deliveries SET eligible_at = CURRENT_TIMESTAMP WHERE state = 'PENDING'")
                    .update();
            awaitCompleted(context, id);
            assertThat(count(context, "indexing_probe", id)).isEqualTo(1);
            assertThat(
                            jdbc(context)
                                    .sql("SELECT attempts FROM event_deliveries")
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(2);
        }
    }

    private ConfigurableApplicationContext start(boolean consumer, boolean polling) {
        return start(consumer, polling, false);
    }

    private ConfigurableApplicationContext start(
            boolean consumer, boolean polling, boolean reconcile) {
        var builder =
                consumer
                        ? new SpringApplicationBuilder(
                                StageConfiguration.class, SecondBrainApplication.class)
                        : new SpringApplicationBuilder(SecondBrainApplication.class);
        var context =
                builder.web(WebApplicationType.NONE)
                        .run(
                                "--spring.config.import=",
                                "--secondbrain.documents.cleanup.enabled=false",
                                "--secondbrain.documents.extraction.enabled=false",
                                "--secondbrain.documents.deletion.enabled=false",
                                "--secondbrain.documents.indexing.reconcile-enabled=" + reconcile,
                                "--secondbrain.documents.indexing.passages.enabled=" + consumer,
                                "--secondbrain.events.polling-enabled=" + polling,
                                "--secondbrain.events.poll-interval=50ms",
                                "--secondbrain.events.initial-backoff=5s",
                                "--secondbrain.events.max-backoff=5s",
                                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                                "--spring.datasource.username=" + POSTGRES.getUsername(),
                                "--spring.datasource.password=" + POSTGRES.getPassword(),
                                "--secondbrain.documents.storage-directory=" + storage);
        jdbc(context)
                .sql(
                        "CREATE TABLE IF NOT EXISTS indexing_probe (document_id uuid NOT NULL, indexing_version bigint NOT NULL, PRIMARY KEY(document_id, indexing_version))")
                .update();
        return context;
    }

    private static UUID fixture(ConfigurableApplicationContext context, long source) {
        var id = UUID.randomUUID();
        context.getBean(JdbcDocumentRepository.class)
                .insert(
                        new DocumentMetadata(
                                id,
                                "fixture.txt",
                                DocumentType.TXT,
                                UUID.randomUUID(),
                                "a".repeat(64),
                                4,
                                source,
                                ExtractionState.EXTRACTED,
                                CREATED,
                                CREATED));
        jdbc(context)
                .sql("INSERT INTO document_extractions VALUES (:id, :version, :at)")
                .param("id", id)
                .param("version", source)
                .param("at", Timestamp.from(CREATED))
                .update();
        jdbc(context)
                .sql(
                        "INSERT INTO document_text_lines(document_id, processing_version, line_number, line_text) VALUES (:id, :version, 1, 'Note')")
                .param("id", id)
                .param("version", source)
                .update();
        return id;
    }

    private static DocumentIndexingService.Receipt requestAfterBarrier(
            ConfigurableApplicationContext context,
            UUID id,
            boolean sameVersion,
            CyclicBarrier barrier)
            throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        var service = context.getBean(DocumentIndexingService.class);
        return (sameVersion
                        ? service.request(id, 7, UUID.randomUUID())
                        : service.requestNext(id, UUID.randomUUID()))
                .orElseThrow();
    }

    private static UUID publishExtraction(
            ConfigurableApplicationContext context, UUID id, long source, UUID correlation) {
        return transaction(context)
                .execute(
                        status ->
                                context.getBean(EventPublisher.class)
                                        .publish(
                                                new EventEnvelope(
                                                        UUID.randomUUID(),
                                                        TextExtractedEvent.TYPE,
                                                        id,
                                                        source,
                                                        CREATED,
                                                        correlation,
                                                        UUID.randomUUID(),
                                                        null,
                                                        Map.of())));
    }

    private static void reconcile(ConfigurableApplicationContext context) {
        new DocumentIndexingReconciliation(
                        context.getBean(JdbcIndexingRepository.class),
                        context.getBean(DocumentIndexingService.class),
                        context.getBean(EventPublisher.class),
                        context.getBeanProvider(DocumentPassageHandler.class))
                .reconcile();
    }

    private static void awaitCompleted(ConfigurableApplicationContext context, UUID id) {
        await().pollInterval(Duration.ofMillis(50))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc(context)
                                                        .sql(
                                                                "SELECT state FROM event_deliveries d JOIN event_keys k USING (event_id) WHERE k.document_id = :id AND d.handler_id = :handler")
                                                        .param("id", id)
                                                        .param("handler", DocumentPassageHandler.ID)
                                                        .query(String.class)
                                                        .optional())
                                        .contains("COMPLETED"));
    }

    private static long count(ConfigurableApplicationContext context, String table, UUID id) {
        // Table names are closed test-owned constants, never user input.
        var query =
                table.equals("event_deliveries")
                        ? "SELECT count(*) FROM event_deliveries d JOIN event_keys k USING (event_id) WHERE k.document_id = :id"
                        : "SELECT count(*) FROM " + table + " WHERE document_id = :id";
        return jdbc(context).sql(query).param("id", id).query(Long.class).single();
    }

    private static JdbcClient jdbc(ConfigurableApplicationContext context) {
        return context.getBean(JdbcClient.class);
    }

    private static TransactionTemplate transaction(ConfigurableApplicationContext context) {
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        tx.setTimeout(5);
        return tx;
    }

    enum Fault {
        PUBLICATION,
        COMMIT
    }

    enum Stale {
        REINDEX,
        SOURCE,
        DELETE
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StageConfiguration {
        @Bean
        Probe indexingProbe(JdbcClient jdbc, EventPublisher events) {
            return new Probe(jdbc, events);
        }

        @Bean
        EventEnvelope.Type indexingProbeType() {
            return PROBED;
        }
    }

    static final class Probe implements DocumentPassageHandler.Stage {
        private final JdbcClient jdbc;
        private final EventPublisher events;
        private final AtomicBoolean fail = new AtomicBoolean();

        Probe(JdbcClient jdbc, EventPublisher events) {
            this.jdbc = jdbc;
            this.events = events;
        }

        @Override
        public EventHandler.Effect prepare(IndexingVersion version, EventEnvelope event) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return () -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                jdbc.sql("INSERT INTO indexing_probe VALUES (:id, :version) ON CONFLICT DO NOTHING")
                        .param("id", version.documentId())
                        .param("version", version.number())
                        .update();
                events.publish(
                        new EventEnvelope(
                                UUID.randomUUID(),
                                PROBED,
                                version.documentId(),
                                version.number(),
                                CREATED,
                                event.correlationId(),
                                event.eventId(),
                                event.traceParent(),
                                Map.of()));
                if (fail.get()) throw new IllegalStateException("test-only-stage-failure");
                return EventHandler.Outcome.COMPLETED;
            };
        }
    }
}
