package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
        // A real cleanup consumer and reconciliation are the next slice; never consume with a
        // no-op.
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
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.config.import=",
                        "--secondbrain.documents.cleanup.enabled=false",
                        "--secondbrain.events.polling-enabled=false",
                        "--secondbrain.documents.extraction.reconcile-enabled=false",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--secondbrain.documents.storage-directory=" + storage);
    }

    private record Fixture(DocumentMetadata document, EventEnvelope accepted) {}
}
