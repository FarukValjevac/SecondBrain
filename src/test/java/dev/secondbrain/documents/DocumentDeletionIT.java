package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;
import dev.secondbrain.events.EventPublisher;

import io.micrometer.core.instrument.MeterRegistry;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Testcontainers
class DocumentDeletionIT {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    private static final byte[] CONTENT =
            "# Study notes\nCafé 📚\n".getBytes(StandardCharsets.UTF_8);
    @TempDir Path storage;

    @AfterEach
    void clearOnlyTheIsolatedTestDatabase() {
        JdbcClient.create(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()))
                .sql(
                        "TRUNCATE TABLE documents, event_keys, domain_events, event_deliveries, event_delivery_attempts CASCADE")
                .update();
    }

    @Test
    void cleanupBacklogIncludesUnscheduledAndFailedRequestsAndIgnoresObsoleteDeliveries()
            throws Exception {
        try (var context = start()) {
            var first = upload(context);
            var second = upload(context);
            var visible = upload(context);
            var now = Instant.parse("2030-01-01T00:00:00Z");
            var documents = context.getBean(JdbcDocumentRepository.class);
            assertThat(documents.cleanupBacklog(now))
                    .isEqualTo(new JdbcDocumentRepository.CleanupBacklog(0, 0, 0));
            // A preserved failure from an older processing version must not classify today's
            // request.
            transaction(context)
                    .executeWithoutResult(
                            status ->
                                    context.getBean(EventPublisher.class)
                                            .publish(
                                                    new EventEnvelope(
                                                            UUID.randomUUID(),
                                                            DocumentDeletionRequestedEvent.TYPE,
                                                            first.document().documentId(),
                                                            1,
                                                            now.minusSeconds(300),
                                                            UUID.randomUUID(),
                                                            null,
                                                            null,
                                                            Map.of())));
            for (var fixture : List.of(first, second)) {
                assertThat(
                                context.getBean(DocumentDeletionService.class)
                                        .request(
                                                fixture.document().documentId(), UUID.randomUUID()))
                        .isTrue();
                jdbc(context)
                        .sql("UPDATE documents SET deleted_at = :time WHERE document_id = :id")
                        .param(
                                "time",
                                Timestamp.from(now.minusSeconds(fixture == first ? 30 : 120)))
                        .param("id", fixture.document().documentId())
                        .update();
            }
            jdbc(context)
                    .sql(
                            """
                    UPDATE event_deliveries d SET state = 'FAILED' FROM event_keys k
                    WHERE d.event_id = k.event_id AND ((k.document_id = :first AND k.processing_version = 1)
                        OR k.document_id = :second OR k.document_id = :visible)
                    """)
                    .param("first", first.document().documentId())
                    .param("second", second.document().documentId())
                    .param("visible", visible.document().documentId())
                    .update();
            // Model a request made before the consumer existed: its envelope is retained, no
            // delivery yet.
            jdbc(context)
                    .sql(
                            """
                    DELETE FROM event_deliveries d USING event_keys k WHERE d.event_id = k.event_id
                        AND k.document_id = :id AND k.processing_version = 2
                    """)
                    .param("id", first.document().documentId())
                    .update();

            assertThat(documents.cleanupBacklog(now))
                    .isEqualTo(new JdbcDocumentRepository.CleanupBacklog(1, 1, 120));
            for (var fixture : List.of(first, second)) {
                transaction(context)
                        .executeWithoutResult(
                                status ->
                                        assertThat(
                                                        documents.finishDeletion(
                                                                fixture.document().documentId(),
                                                                2,
                                                                fixture.document().storageId()))
                                                .isTrue());
            }
            assertThat(documents.cleanupBacklog(now))
                    .isEqualTo(new JdbcDocumentRepository.CleanupBacklog(0, 0, 0));
        }
    }

    @Test
    void reconcilesRetainedRequestsAfterRestartAndRemovesAllContentAndObsoleteProcessingState()
            throws Exception {
        UUID id;
        UUID storageId;
        try (var context = start(false, false, false)) {
            var fixture = upload(context);
            id = fixture.document().documentId();
            storageId = fixture.document().storageId();
            var text =
                    context.getBean(DocumentTextExtractionService.class)
                            .extract(fixture.document());
            assertThat(
                            transaction(context)
                                    .<Boolean>execute(
                                            status ->
                                                    context.getBean(
                                                                    DocumentExtractionCompletion
                                                                            .class)
                                                            .complete(text, fixture.accepted())))
                    .isTrue();
            context.getBean(JdbcExtractedTextRepository.class)
                    .insert(new ExtractedText(id, 9, text.extractedAt(), text.lines()));
            assertThat(
                            context.getBean(DocumentDeletionService.class)
                                    .request(id, UUID.randomUUID()))
                    .isTrue();
            assertThat(
                            jdbc(context)
                                    .sql(
                                            """
                    SELECT count(*) FROM event_deliveries d JOIN event_keys k USING (event_id)
                    WHERE k.document_id = :id AND k.event_type = 'DocumentDeletionRequested'
                    """)
                                    .param("id", id)
                                    .query(Integer.class)
                                    .single())
                    .isZero();
        }
        try (var context = start(true, true, true)) {
            awaitRemoved(context, id);
            assertThat(Files.exists(storage.resolve(storageId + ".original"))).isFalse();
            assertThat(
                            context.getBean(JdbcExtractedTextRepository.class)
                                    .findByDocumentVersion(id, 1))
                    .isEmpty();
            assertThat(
                            context.getBean(JdbcExtractedTextRepository.class)
                                    .findByDocumentVersion(id, 9))
                    .isEmpty();
            assertThat(
                            jdbc(context)
                                    .sql(
                                            """
                    SELECT count(*) FROM domain_events e JOIN event_keys k USING (event_id)
                    WHERE k.document_id = :id AND k.processing_version < 2
                    """)
                                    .param("id", id)
                                    .query(Integer.class)
                                    .single())
                    .isZero();
            assertThat(eventCount(context, id, "DocumentAccepted")).isEqualTo(1);
            assertThat(eventCount(context, id, "TextExtracted")).isEqualTo(1);
            assertThat(cleanupState(context, id)).isEqualTo("COMPLETED");
            assertThat(context.getBean(JdbcDocumentRepository.class).cleanupBacklog(Instant.now()))
                    .isEqualTo(new JdbcDocumentRepository.CleanupBacklog(0, 0, 0));
            assertThat(
                            context.getBean(DocumentDeletionHandler.class)
                                    .prepare(deletionEvent(context, id))
                                    .apply())
                    .isEqualTo(EventHandler.Outcome.SKIPPED);
            // Reconciliation cannot create a duplicate delivery or revive cleaned content.
            context.getBean(DocumentDeletionReconciliation.class).reconcile();
            assertThat(
                            jdbc(context)
                                    .sql(
                                            """
                    SELECT count(*) FROM event_deliveries d JOIN event_keys k USING (event_id)
                    WHERE k.document_id = :id
                    """)
                                    .param("id", id)
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(1);
            assertHidden(context, id);
        }
    }

    @Test
    void canRetryAfterTheFileWasRemovedButDatabaseFinalizationRolledBack() throws Exception {
        try (var context = start()) {
            var fixture = upload(context);
            var id = fixture.document().documentId();
            var text =
                    context.getBean(DocumentTextExtractionService.class)
                            .extract(fixture.document());
            assertThat(
                            transaction(context)
                                    .<Boolean>execute(
                                            status ->
                                                    context.getBean(
                                                                    DocumentExtractionCompletion
                                                                            .class)
                                                            .complete(text, fixture.accepted())))
                    .isTrue();
            assertThat(
                            context.getBean(DocumentDeletionService.class)
                                    .request(id, UUID.randomUUID()))
                    .isTrue();
            var handler = context.getBean(DocumentDeletionHandler.class);
            var event = deletionEvent(context, id);
            var prepared = handler.prepare(event);
            assertThat(Files.exists(storage.resolve(fixture.document().storageId() + ".original")))
                    .isFalse();
            transaction(context)
                    .executeWithoutResult(
                            status -> {
                                assertThat(prepared.apply())
                                        .isEqualTo(EventHandler.Outcome.COMPLETED);
                                status.setRollbackOnly();
                            });
            assertThat(context.getBean(JdbcDocumentRepository.class).deletedStorageId(id, 2))
                    .isPresent();
            assertThat(
                            context.getBean(JdbcExtractedTextRepository.class)
                                    .findByDocumentVersion(id, 1))
                    .contains(text);
            assertThat(
                            jdbc(context)
                                    .sql(
                                            """
                    SELECT count(*) FROM domain_events e JOIN event_keys k USING (event_id)
                    WHERE k.document_id = :id AND k.event_type = 'DocumentAccepted'
                    """)
                                    .param("id", id)
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(1);
        }
        // A fresh process sees the missing file as success and acknowledges only after DB commit.
        // Find the sole pending deletion in this isolated database; other tests are cleared between
        // cases.
        try (var context = start(true, true, false)) {
            var id =
                    jdbc(context)
                            .sql(
                                    "SELECT document_id FROM event_keys WHERE event_type = 'DocumentDeletionRequested'")
                            .query(UUID.class)
                            .single();
            awaitRemoved(context, id);
            assertThat(cleanupState(context, id)).isEqualTo("COMPLETED");
        }
    }

    @Test
    void storageFailureLeavesHiddenRecoverableWorkAndTheWorkerRetries() throws Exception {
        try (var context = start(true, true, false)) {
            var fixture = upload(context);
            var id = fixture.document().documentId();
            var gate = context.getBean(CleanupGate.class);
            gate.fail.set(true);
            assertThat(
                            context.getBean(DocumentDeletionService.class)
                                    .request(id, UUID.randomUUID()))
                    .isTrue();
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                            () -> {
                                assertThat(cleanupState(context, id)).isEqualTo("PENDING");
                                assertThat(
                                                jdbc(context)
                                                        .sql(
                                                                """
                        SELECT attempts FROM event_deliveries d JOIN event_keys k USING (event_id)
                        WHERE k.document_id = :id AND d.handler_id = :handler
                        """)
                                                        .param("id", id)
                                                        .param(
                                                                "handler",
                                                                DocumentDeletionHandler.ID)
                                                        .query(Integer.class)
                                                        .single())
                                        .isGreaterThanOrEqualTo(1);
                            });
            assertHidden(context, id);
            assertThat(context.getBean(JdbcDocumentRepository.class).deletedStorageId(id, 2))
                    .isPresent();
            assertThat(
                            Files.readAllBytes(
                                    storage.resolve(fixture.document().storageId() + ".original")))
                    .isEqualTo(CONTENT);
            gate.fail.set(false);
            awaitRemoved(context, id);
            assertThat(cleanupState(context, id)).isEqualTo("COMPLETED");
        }
    }

    private static void awaitRemoved(ConfigurableApplicationContext context, UUID id) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc(context)
                                                        .sql(
                                                                "SELECT count(*) FROM documents WHERE document_id = :id")
                                                        .param("id", id)
                                                        .query(Integer.class)
                                                        .single())
                                        .isZero());
    }

    private static String cleanupState(ConfigurableApplicationContext context, UUID id) {
        return jdbc(context)
                .sql(
                        """
                SELECT d.state FROM event_deliveries d JOIN event_keys k USING (event_id)
                WHERE k.document_id = :id AND d.handler_id = :handler
                """)
                .param("id", id)
                .param("handler", DocumentDeletionHandler.ID)
                .query(String.class)
                .single();
    }

    private static EventEnvelope deletionEvent(ConfigurableApplicationContext context, UUID id) {
        return jdbc(context)
                .sql(
                        """
                SELECT e.event_id, e.occurred_at, e.correlation_id FROM domain_events e JOIN event_keys k USING(event_id)
                WHERE k.document_id = :id AND k.event_type = 'DocumentDeletionRequested'
                """)
                .param("id", id)
                .query(
                        (row, index) ->
                                new EventEnvelope(
                                        row.getObject("event_id", UUID.class),
                                        DocumentDeletionRequestedEvent.TYPE,
                                        id,
                                        2,
                                        row.getTimestamp("occurred_at").toInstant(),
                                        row.getObject("correlation_id", UUID.class),
                                        null,
                                        null,
                                        Map.of()))
                .single();
    }

    @Test
    void hidesCompletedTextAndRecordsOneDurableRequestAcrossRestartWithoutRemovingOriginals()
            throws Exception {
        UUID id;
        UUID storageId;
        var correlation = UUID.randomUUID();
        try (var context = start()) {
            var fixture = upload(context);
            id = fixture.document().documentId();
            storageId = fixture.document().storageId();
            var text =
                    context.getBean(DocumentTextExtractionService.class)
                            .extract(fixture.document());
            assertThat(
                            transaction(context)
                                    .<Boolean>execute(
                                            status ->
                                                    context.getBean(
                                                                    DocumentExtractionCompletion
                                                                            .class)
                                                            .complete(text, fixture.accepted())))
                    .isTrue();
            var inspection = context.getBean(DocumentInspectionService.class);
            assertThat(inspection.find(id).orElseThrow().lines()).isNotEmpty();

            var service = context.getBean(DocumentDeletionService.class);
            assertThat(service.request(id, correlation)).isTrue();
            var deletedAt =
                    jdbc(context)
                            .sql("SELECT deleted_at FROM documents WHERE document_id = :id")
                            .param("id", id)
                            .query(Timestamp.class)
                            .single();
            assertThat(service.request(id, UUID.randomUUID())).isFalse();
            assertThat(service.request(UUID.randomUUID(), UUID.randomUUID())).isFalse();
            assertThat(
                            jdbc(context)
                                    .sql("SELECT deleted_at FROM documents WHERE document_id = :id")
                                    .param("id", id)
                                    .query(Timestamp.class)
                                    .single())
                    .isEqualTo(deletedAt);
            assertHidden(context, id);
            assertRequest(context, id, correlation);
            assertThat(context.getBean(JdbcDocumentRepository.class).ownsStorageId(storageId))
                    .isTrue();
            assertThat(
                            context.getBean(JdbcExtractedTextRepository.class)
                                    .findByDocumentVersion(id, 1))
                    .contains(text);
            assertThat(Files.readAllBytes(storage.resolve(storageId + ".original")))
                    .isEqualTo(CONTENT);
            assertThat(counter(context, "requested")).isEqualTo(1);
            assertThat(counter(context, "unchanged")).isEqualTo(2);
        }
        try (var context = start()) {
            assertHidden(context, id);
            assertRequest(context, id, correlation);
            assertThat(Files.readAllBytes(storage.resolve(storageId + ".original")))
                    .isEqualTo(CONTENT);
            assertThat(
                            context.getBean(JdbcExtractedTextRepository.class)
                                    .findByDocumentVersion(id, 1))
                    .isPresent();
        }
    }

    @ParameterizedTest(name = "deletion event failure: {0}")
    @ValueSource(strings = {"NOT DEFERRABLE", "DEFERRABLE INITIALLY DEFERRED"})
    void rollsBackTheMarkerVersionAndEventOnPublicationOrCommitFailure(String timing)
            throws Exception {
        try (var context = start()) {
            var fixture = upload(context);
            var id = fixture.document().documentId();
            var jdbc = jdbc(context);
            jdbc.sql("CREATE TABLE deletion_test_correlations (id uuid PRIMARY KEY)").update();
            try {
                jdbc.sql(
                                "ALTER TABLE domain_events ADD CONSTRAINT deletion_test_event_failure "
                                        + "FOREIGN KEY (correlation_id) REFERENCES deletion_test_correlations(id) "
                                        + timing
                                        + " NOT VALID")
                        .update();
                try {
                    assertThatThrownBy(
                                    () ->
                                            context.getBean(DocumentDeletionService.class)
                                                    .request(id, UUID.randomUUID()))
                            .rootCause()
                            .isInstanceOfSatisfying(
                                    SQLException.class,
                                    failure ->
                                            assertThat(failure.getSQLState()).isEqualTo("23503"));
                    assertVisibleWithoutRequest(context, fixture);
                    assertThat(counter(context, "requested")).isZero();
                    assertThat(counter(context, "unavailable")).isEqualTo(1);
                } finally {
                    jdbc.sql(
                                    "ALTER TABLE domain_events DROP CONSTRAINT deletion_test_event_failure")
                            .update();
                }
            } finally {
                jdbc.sql("DROP TABLE deletion_test_correlations").update();
            }
            assertThat(
                            context.getBean(DocumentDeletionService.class)
                                    .request(id, UUID.randomUUID()))
                    .isTrue();
            assertHidden(context, id);
        }
    }

    @Test
    void joinsCallerRollbackAndDoesNotReportACommittedDeletion() throws Exception {
        try (var context = start()) {
            var fixture = upload(context);
            transaction(context)
                    .executeWithoutResult(
                            status -> {
                                assertThat(
                                                context.getBean(DocumentDeletionService.class)
                                                        .request(
                                                                fixture.document().documentId(),
                                                                UUID.randomUUID()))
                                        .isTrue();
                                assertThat(counter(context, "requested")).isZero();
                                status.setRollbackOnly();
                            });
            assertVisibleWithoutRequest(context, fixture);
            assertThat(counter(context, "requested")).isZero();
            assertThat(counter(context, "unavailable")).isEqualTo(1);
        }
    }

    @Test
    void concurrentRequestsCommitOnlyOneVersionIncrementAndEvent() throws Exception {
        try (var context = start();
                var workers = Executors.newFixedThreadPool(2)) {
            var fixture = upload(context);
            var id = fixture.document().documentId();
            var service = context.getBean(DocumentDeletionService.class);
            var correlation = UUID.randomUUID();
            var ready = new CyclicBarrier(2);
            var results =
                    workers.<Boolean>invokeAll(
                            List.of(
                                    () -> {
                                        ready.await(10, TimeUnit.SECONDS);
                                        return service.request(id, correlation);
                                    },
                                    () -> {
                                        ready.await(10, TimeUnit.SECONDS);
                                        return service.request(id, correlation);
                                    }),
                            15,
                            TimeUnit.SECONDS);
            assertThat(
                            List.of(
                                    results.get(0).get(1, TimeUnit.SECONDS),
                                    results.get(1).get(1, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertRequest(context, id, correlation);
            assertHidden(context, id);
        }
    }

    @Test
    void preparedExtractionAndLateFailureCannotChangeADeletedDocumentOrPublishText()
            throws Exception {
        try (var context = start()) {
            var fixture = upload(context);
            var id = fixture.document().documentId();
            var handler = context.getBean(DocumentExtractionHandler.class);
            var prepared = handler.prepare(fixture.accepted());
            assertThat(
                            context.getBean(DocumentDeletionService.class)
                                    .request(id, UUID.randomUUID()))
                    .isTrue();

            assertThat(
                            transaction(context)
                                    .<EventHandler.Outcome>execute(status -> prepared.apply()))
                    .isEqualTo(EventHandler.Outcome.SKIPPED);
            assertThat(
                            transaction(context)
                                    .<Boolean>execute(
                                            status ->
                                                    context.getBean(
                                                                    DocumentExtractionCompletion
                                                                            .class)
                                                            .fail(
                                                                    fixture.accepted(),
                                                                    TextExtractionException.Reason
                                                                            .SOURCE_UNAVAILABLE)))
                    .isFalse();
            // Also reject the new version: visibility is an independent fence, not only a version
            // mismatch.
            var repository = context.getBean(JdbcDocumentRepository.class);
            assertThat(repository.markExtracted(id, 2, fixture.document().createdAt())).isFalse();
            assertThat(
                            repository.markFailed(
                                    id,
                                    2,
                                    TextExtractionException.Reason.SOURCE_UNAVAILABLE,
                                    fixture.document().createdAt()))
                    .isFalse();
            assertThat(handler.prepare(fixture.accepted()).apply())
                    .isEqualTo(EventHandler.Outcome.SKIPPED);
            assertThat(
                            context.getBean(DocumentInspectionService.class)
                                    .retry(id, UUID.randomUUID()))
                    .isFalse();
            assertThat(
                            context.getBean(JdbcExtractedTextRepository.class)
                                    .findByDocumentVersion(id, 1))
                    .isEmpty();
            assertThat(eventCount(context, id, "TextExtracted")).isZero();
            assertHidden(context, id);
        }
    }

    private static void assertHidden(ConfigurableApplicationContext context, UUID id) {
        var inspection = context.getBean(DocumentInspectionService.class);
        assertThat(inspection.find(id)).isEmpty();
        assertThat(inspection.list(0).documents())
                .noneMatch(document -> document.documentId().equals(id));
        assertThat(context.getBean(JdbcDocumentRepository.class).findById(id)).isEmpty();
    }

    private static void assertVisibleWithoutRequest(
            ConfigurableApplicationContext context, Fixture fixture) {
        var id = fixture.document().documentId();
        assertThat(context.getBean(JdbcDocumentRepository.class).findById(id))
                .contains(fixture.document());
        assertThat(context.getBean(DocumentInspectionService.class).find(id)).isPresent();
        assertThat(
                        jdbc(context)
                                .sql(
                                        "SELECT deleted_at IS NULL FROM documents WHERE document_id = :id")
                                .param("id", id)
                                .query(Boolean.class)
                                .single())
                .isTrue();
        assertThat(eventCount(context, id, "DocumentDeletionRequested")).isZero();
    }

    private static void assertRequest(
            ConfigurableApplicationContext context, UUID id, UUID correlation) {
        assertThat(
                        jdbc(context)
                                .sql(
                                        "SELECT processing_version FROM documents WHERE document_id = :id")
                                .param("id", id)
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
        assertThat(eventCount(context, id, "DocumentDeletionRequested")).isEqualTo(1);
        var event =
                jdbc(context)
                        .sql(
                                """
                SELECT k.processing_version, k.schema_version, e.correlation_id, e.causation_id,
                    e.reference_ids::text AS refs
                FROM event_keys k JOIN domain_events e USING (event_id)
                WHERE k.document_id = :id AND k.event_type = 'DocumentDeletionRequested'
                """)
                        .param("id", id)
                        .query()
                        .singleRow();
        assertThat(((Number) event.get("processing_version")).longValue()).isEqualTo(2);
        assertThat(event.get("schema_version")).isEqualTo(1);
        assertThat(event.get("correlation_id")).isEqualTo(correlation);
        assertThat(event.get("causation_id")).isNull();
        assertThat(event.get("refs")).isEqualTo("{}");
        // The real cleanup delivery commits with the request; polling is disabled in this fixture.
        assertThat(
                        jdbc(context)
                                .sql(
                                        """
                SELECT count(*) FROM event_deliveries d JOIN event_keys k USING (event_id)
                WHERE k.document_id = :id AND k.event_type = 'DocumentDeletionRequested'
                """)
                                .param("id", id)
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
    }

    private static int eventCount(ConfigurableApplicationContext context, UUID id, String type) {
        return jdbc(context)
                .sql(
                        "SELECT count(*) FROM event_keys WHERE document_id = :id AND event_type = :type")
                .param("id", id)
                .param("type", type)
                .query(Integer.class)
                .single();
    }

    private static double counter(ConfigurableApplicationContext context, String outcome) {
        return context.getBean(MeterRegistry.class)
                .get("secondbrain.document.deletions")
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private static Fixture upload(ConfigurableApplicationContext context) throws Exception {
        var id =
                context.getBean(DocumentAcceptanceService.class)
                        .accept(
                                "Notes.md",
                                CONTENT.length,
                                new ByteArrayInputStream(CONTENT),
                                UUID.randomUUID())
                        .documentId();
        var document = context.getBean(JdbcDocumentRepository.class).findById(id).orElseThrow();
        var accepted =
                jdbc(context)
                        .sql(
                                """
                SELECT e.event_id, e.occurred_at, e.correlation_id FROM domain_events e
                JOIN event_keys k USING (event_id) WHERE k.document_id = :id AND k.event_type = 'DocumentAccepted'
                """)
                        .param("id", id)
                        .query(
                                (row, index) ->
                                        new EventEnvelope(
                                                row.getObject("event_id", UUID.class),
                                                DocumentAcceptedEvent.TYPE,
                                                id,
                                                1,
                                                row.getTimestamp("occurred_at").toInstant(),
                                                row.getObject("correlation_id", UUID.class),
                                                null,
                                                null,
                                                Map.of()))
                        .single();
        return new Fixture(document, accepted);
    }

    private static TransactionTemplate transaction(ConfigurableApplicationContext context) {
        var transaction =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        transaction.setTimeout(5);
        return transaction;
    }

    private static JdbcClient jdbc(ConfigurableApplicationContext context) {
        return context.getBean(JdbcClient.class);
    }

    private ConfigurableApplicationContext start() {
        return start(false, true, false);
    }

    private ConfigurableApplicationContext start(
            boolean polling, boolean cleanup, boolean reconcile) {
        return new SpringApplicationBuilder(
                        SecondBrainApplication.class, CleanupConfiguration.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.config.import=",
                        "--secondbrain.documents.cleanup.enabled=false",
                        "--secondbrain.events.polling-enabled=" + polling,
                        "--secondbrain.events.poll-interval=50ms",
                        "--secondbrain.events.initial-backoff=1s",
                        "--secondbrain.events.max-backoff=1s",
                        "--secondbrain.events.max-attempts=5",
                        "--secondbrain.documents.extraction.enabled=" + !polling,
                        "--secondbrain.documents.deletion.enabled=" + cleanup,
                        "--secondbrain.documents.deletion.reconcile-enabled=" + reconcile,
                        "--secondbrain.documents.extraction.reconcile-enabled=false",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--secondbrain.documents.storage-directory=" + storage);
    }

    private record Fixture(DocumentMetadata document, EventEnvelope accepted) {}

    static final class CleanupGate {
        final AtomicBoolean fail = new AtomicBoolean();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CleanupConfiguration {
        @Bean
        CleanupGate cleanupGate() {
            return new CleanupGate();
        }

        @Bean
        @Primary
        OriginalFileStore controlledOriginals(
                @Value("${secondbrain.documents.storage-directory}") String directory,
                DocumentUploadPolicy policy,
                CleanupGate gate)
                throws IOException {
            var originals = spy(new OriginalFileStore(directory, policy));
            doAnswer(
                            invocation -> {
                                assertThat(
                                                TransactionSynchronizationManager
                                                        .isActualTransactionActive())
                                        .isFalse();
                                if (gate.fail.get())
                                    throw new IOException("sentinel-private-delete-error");
                                return invocation.callRealMethod();
                            })
                    .when(originals)
                    .delete(any(UUID.class));
            return originals;
        }
    }
}
