package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.events.EventEnvelope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Testcontainers
class DocumentExtractionCompletionIT {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir private Path storageDirectory;

    @Test
    void commitsTextStateAndOneCorrelatedEventAndDoesNotOverwriteOnDuplicate() throws Exception {
        try (var context = startApplication()) {
            var fixture = fixture(context);
            var completion = context.getBean(DocumentExtractionCompletion.class);

            assertThat(
                            transaction(context)
                                    .<Boolean>execute(
                                            status ->
                                                    completion.complete(
                                                            fixture.text(), fixture.accepted())))
                    .isTrue();
            var replacement =
                    new ExtractedText(
                            fixture.text().documentId(),
                            1,
                            fixture.text().extractedAt().plusSeconds(1),
                            List.of(new ExtractedText.Line(1, "Replacement")));
            assertThat(
                            transaction(context)
                                    .<Boolean>execute(
                                            status ->
                                                    completion.complete(
                                                            replacement, fixture.accepted())))
                    .isFalse();

            assertCompleted(context, fixture);
            var event =
                    jdbc(context)
                            .sql(
                                    """
                    SELECT e.correlation_id, e.causation_id, e.reference_ids::text AS reference_ids,
                           k.schema_version, k.processing_version
                    FROM domain_events e JOIN event_keys k USING (event_id)
                    WHERE k.document_id = :id AND k.event_type = 'TextExtracted'
                    """)
                            .param("id", fixture.text().documentId())
                            .query()
                            .singleRow();
            assertThat(event.get("correlation_id")).isEqualTo(fixture.accepted().correlationId());
            assertThat(event.get("causation_id")).isEqualTo(fixture.accepted().eventId());
            assertThat(event.get("reference_ids")).isEqualTo("{}");
            assertThat(((Number) event.get("schema_version")).intValue()).isEqualTo(1);
            assertThat(((Number) event.get("processing_version")).longValue()).isEqualTo(1);
            // TextExtracted is registered but has no indexing consumer yet.
            assertThat(
                            jdbc(context)
                                    .sql(
                                            """
                    SELECT count(*) FROM event_deliveries d JOIN event_keys k USING (event_id)
                    WHERE k.document_id = :id AND k.event_type = 'TextExtracted'
                    """)
                                    .param("id", fixture.text().documentId())
                                    .query(Integer.class)
                                    .single())
                    .isZero();
        }
    }

    @ParameterizedTest(name = "downstream event constraint timing: {0}")
    @ValueSource(strings = {"NOT DEFERRABLE", "DEFERRABLE INITIALLY DEFERRED"})
    void rollsBackTextStateAndEventOnInsertOrCommitFailureAndCanRetry(String timing)
            throws Exception {
        try (var context = startApplication()) {
            var fixture = fixture(context);
            var jdbc = jdbc(context);
            var completion = context.getBean(DocumentExtractionCompletion.class);
            jdbc.sql("CREATE TABLE extraction_test_correlations (id uuid PRIMARY KEY)").update();
            try {
                jdbc.sql(
                                """
                        ALTER TABLE domain_events ADD CONSTRAINT extraction_test_event_failure
                        FOREIGN KEY (correlation_id) REFERENCES extraction_test_correlations(id)
                        """
                                        + timing
                                        + " NOT VALID")
                        .update();
                try {
                    assertThatThrownBy(
                                    () ->
                                            transaction(context)
                                                    .<Boolean>execute(
                                                            status ->
                                                                    completion.complete(
                                                                            fixture.text(),
                                                                            fixture.accepted())))
                            .rootCause()
                            .isInstanceOfSatisfying(
                                    SQLException.class,
                                    failure ->
                                            assertThat(failure.getSQLState()).isEqualTo("23503"));
                    assertNotCompleted(context, fixture);
                } finally {
                    jdbc.sql(
                                    "ALTER TABLE domain_events DROP CONSTRAINT extraction_test_event_failure")
                            .update();
                }
            } finally {
                jdbc.sql("DROP TABLE extraction_test_correlations").update();
            }

            assertThat(
                            transaction(context)
                                    .<Boolean>execute(
                                            status ->
                                                    completion.complete(
                                                            fixture.text(), fixture.accepted())))
                    .isTrue();
            assertCompleted(context, fixture);
        }
    }

    @Test
    void rollsBackWithTheEnclosingCallerTransaction() throws Exception {
        try (var context = startApplication()) {
            var fixture = fixture(context);
            transaction(context)
                    .executeWithoutResult(
                            status -> {
                                assertThat(
                                                context.getBean(DocumentExtractionCompletion.class)
                                                        .complete(
                                                                fixture.text(), fixture.accepted()))
                                        .isTrue();
                                status.setRollbackOnly();
                            });

            assertNotCompleted(context, fixture);
        }
    }

    @Test
    void skipsMissingOrSupersededDocumentsWithoutPublishingOldText() throws Exception {
        try (var context = startApplication()) {
            var fixture = fixture(context);
            var id = fixture.text().documentId();
            jdbc(context)
                    .sql("UPDATE documents SET processing_version = 2 WHERE document_id = :id")
                    .param("id", id)
                    .update();
            var completion = context.getBean(DocumentExtractionCompletion.class);

            assertThat(
                            transaction(context)
                                    .<Boolean>execute(
                                            status ->
                                                    completion.complete(
                                                            fixture.text(), fixture.accepted())))
                    .isFalse();
            assertNotCompleted(context, fixture);

            jdbc(context)
                    .sql("DELETE FROM documents WHERE document_id = :id")
                    .param("id", id)
                    .update();
            assertThat(
                            transaction(context)
                                    .<Boolean>execute(
                                            status ->
                                                    completion.complete(
                                                            fixture.text(), fixture.accepted())))
                    .isFalse();
            assertThat(eventCount(context, id)).isZero();
        }
    }

    @Test
    void refusesToCommitIndependentlyWithoutACallerTransaction() throws Exception {
        try (var context = startApplication()) {
            var fixture = fixture(context);

            assertThatThrownBy(
                            () ->
                                    context.getBean(DocumentExtractionCompletion.class)
                                            .complete(fixture.text(), fixture.accepted()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertNotCompleted(context, fixture);
        }
    }

    @Test
    void onlyOneConcurrentCompletionCommits() throws Exception {
        try (var context = startApplication();
                var workers = Executors.newFixedThreadPool(2)) {
            var fixture = fixture(context);
            var completion = context.getBean(DocumentExtractionCompletion.class);
            var ready = new CyclicBarrier(2);
            var results =
                    workers.<Boolean>invokeAll(
                            List.of(
                                    () -> {
                                        ready.await(10, TimeUnit.SECONDS);
                                        return transaction(context)
                                                .<Boolean>execute(
                                                        status ->
                                                                completion.complete(
                                                                        fixture.text(),
                                                                        fixture.accepted()));
                                    },
                                    () -> {
                                        ready.await(10, TimeUnit.SECONDS);
                                        return transaction(context)
                                                .<Boolean>execute(
                                                        status ->
                                                                completion.complete(
                                                                        fixture.text(),
                                                                        fixture.accepted()));
                                    }),
                            15,
                            TimeUnit.SECONDS);

            assertThat(
                            List.of(
                                    results.get(0).get(1, TimeUnit.SECONDS),
                                    results.get(1).get(1, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertCompleted(context, fixture);
        }
    }

    private static void assertCompleted(ConfigurableApplicationContext context, Fixture fixture) {
        assertThat(
                        context.getBean(JdbcDocumentRepository.class)
                                .findById(fixture.text().documentId())
                                .orElseThrow()
                                .extractionState())
                .isEqualTo(ExtractionState.EXTRACTED);
        assertThat(
                        context.getBean(JdbcExtractedTextRepository.class)
                                .findByDocumentVersion(fixture.text().documentId(), 1))
                .contains(fixture.text());
        assertThat(eventCount(context, fixture.text().documentId())).isEqualTo(1);
    }

    private static void assertNotCompleted(
            ConfigurableApplicationContext context, Fixture fixture) {
        var metadata =
                context.getBean(JdbcDocumentRepository.class)
                        .findById(fixture.text().documentId())
                        .orElseThrow();
        assertThat(metadata.extractionState()).isEqualTo(ExtractionState.QUEUED);
        assertThat(metadata.updatedAt()).isEqualTo(fixture.originalUpdatedAt());
        assertThat(
                        context.getBean(JdbcExtractedTextRepository.class)
                                .findByDocumentVersion(fixture.text().documentId(), 1))
                .isEmpty();
        assertThat(eventCount(context, fixture.text().documentId())).isZero();
    }

    private static int eventCount(ConfigurableApplicationContext context, UUID documentId) {
        return jdbc(context)
                .sql(
                        """
                SELECT count(*) FROM event_keys WHERE document_id = :id AND event_type = 'TextExtracted'
                """)
                .param("id", documentId)
                .query(Integer.class)
                .single();
    }

    private static Fixture fixture(ConfigurableApplicationContext context) throws Exception {
        var bytes = "# Café 📚\n\nStudy\n".getBytes(StandardCharsets.UTF_8);
        UUID id;
        try (var input = new ByteArrayInputStream(bytes)) {
            id =
                    context.getBean(DocumentAcceptanceService.class)
                            .accept("fixture.md", bytes.length, input, UUID.randomUUID())
                            .documentId();
        }
        var document = context.getBean(JdbcDocumentRepository.class).findById(id).orElseThrow();
        var text = context.getBean(DocumentTextExtractionService.class).extract(document);
        var accepted =
                jdbc(context)
                        .sql(
                                """
                SELECT e.event_id, e.occurred_at, e.correlation_id, e.trace_parent
                FROM domain_events e JOIN event_keys k USING (event_id)
                WHERE k.document_id = :id AND k.event_type = 'DocumentAccepted'
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
                                                row.getString("trace_parent"),
                                                Map.of()))
                        .single();
        return new Fixture(text, accepted, document.updatedAt());
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

    private ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(SecondBrainApplication.class, ClockConfiguration.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.config.import=",
                        "--secondbrain.documents.cleanup.enabled=false",
                        "--secondbrain.events.polling-enabled=false",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--secondbrain.documents.storage-directory=" + storageDirectory);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        Clock extractionTestClock() {
            return Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456Z"), ZoneOffset.UTC);
        }
    }

    private record Fixture(ExtractedText text, EventEnvelope accepted, Instant originalUpdatedAt) {}
}
