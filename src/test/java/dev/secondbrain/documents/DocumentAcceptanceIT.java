package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.secondbrain.SecondBrainApplication;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Testcontainers
class DocumentAcceptanceIT {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir private Path storageDirectory;

    @ParameterizedTest(name = "event constraint timing: {0}")
    @ValueSource(strings = {"NOT DEFERRABLE", "DEFERRABLE INITIALLY DEFERRED"})
    void rollsBackAndCompensatesWhenEventInsertOrCommitFails(String constraintTiming)
            throws Exception {
        var content = "Rollback fixture".getBytes(StandardCharsets.UTF_8);
        try (var context = startApplication(storageDirectory)) {
            var service = context.getBean(DocumentAcceptanceService.class);
            var jdbc = context.getBean(JdbcClient.class);
            var meters = context.getBean(MeterRegistry.class);
            var before = rowCounts(jdbc);

            // An empty reference table rejects new events either at INSERT or at COMMIT.
            // NOT VALID leaves records from other tests untouched. This is container-only DDL.
            jdbc.sql("CREATE TABLE upload_test_correlations (id uuid PRIMARY KEY)").update();
            try {
                jdbc.sql(
                                """
                                ALTER TABLE domain_events ADD CONSTRAINT upload_test_event_failure
                                FOREIGN KEY (correlation_id) REFERENCES upload_test_correlations(id)
                                """
                                        + constraintTiming
                                        + " NOT VALID")
                        .update();
                try {
                    assertThatThrownBy(
                                    () ->
                                            service.accept(
                                                    "rollback.txt",
                                                    content.length,
                                                    new ByteArrayInputStream(content),
                                                    UUID.randomUUID()))
                            .rootCause()
                            .isInstanceOfSatisfying(
                                    SQLException.class,
                                    failure ->
                                            assertThat(failure.getSQLState()).isEqualTo("23503"));

                    assertThat(rowCounts(jdbc)).isEqualTo(before);
                    try (var files = Files.list(storageDirectory)) {
                        assertThat(files).isEmpty();
                    }
                    assertThat(
                                    meters.get("secondbrain.document.uploads")
                                            .tag("outcome", "accepted")
                                            .counter()
                                            .count())
                            .isZero();
                    assertThat(
                                    meters.get("secondbrain.document.uploads")
                                            .tag("outcome", "unavailable")
                                            .counter()
                                            .count())
                            .isEqualTo(1);
                } finally {
                    jdbc.sql("ALTER TABLE domain_events DROP CONSTRAINT upload_test_event_failure")
                            .update();
                }
            } finally {
                jdbc.sql("DROP TABLE upload_test_correlations").update();
            }

            var recovered =
                    service.accept(
                            "retry.txt",
                            content.length,
                            new ByteArrayInputStream(content),
                            UUID.randomUUID());
            assertThat(
                            context.getBean(JdbcDocumentRepository.class)
                                    .findById(recovered.documentId()))
                    .isPresent();
            assertThat(rowCounts(jdbc).get("domain_events"))
                    .isEqualTo(before.get("domain_events") + 1);
            assertThat(
                            meters.get("secondbrain.document.uploads")
                                    .tag("outcome", "accepted")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void commitsDocumentAndDurableAcceptedEventAndKeepsOriginalPrivate() throws Exception {
        var content = "My meeting notes".getBytes(StandardCharsets.UTF_8);
        var correlationId = UUID.randomUUID();
        try (var context = startApplication(storageDirectory)) {
            var service = context.getBean(DocumentAcceptanceService.class);
            var jdbc = context.getBean(JdbcClient.class);
            var meters = context.getBean(MeterRegistry.class);

            var document =
                    service.accept(
                            "meeting notes.md",
                            content.length,
                            new ByteArrayInputStream(content),
                            correlationId);

            assertThat(
                            meters.get("secondbrain.document.uploads")
                                    .tag("outcome", "accepted")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
            assertThat(
                            meters.get("secondbrain.document.upload.duration")
                                    .tag("outcome", "accepted")
                                    .timer()
                                    .count())
                    .isEqualTo(1);

            assertThat(
                            jdbc.sql(
                                            "SELECT extraction_state FROM documents WHERE document_id = :id")
                                    .param("id", document.documentId())
                                    .query(String.class)
                                    .single())
                    .isEqualTo("QUEUED");
            assertThat(
                            jdbc.sql(
                                            """
                                            SELECT count(*)
                                            FROM domain_events event
                                            JOIN event_keys key USING (event_id)
                                            WHERE key.document_id = :documentId
                                              AND key.event_type = 'DocumentAccepted'
                                              AND event.correlation_id = :correlationId
                                            """)
                                    .param("documentId", document.documentId())
                                    .param("correlationId", correlationId)
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(1);
            var metadata =
                    context.getBean(JdbcDocumentRepository.class)
                            .findById(document.documentId())
                            .orElseThrow();
            assertThat(
                            Files.readAllBytes(
                                    storageDirectory.resolve(metadata.storageId() + ".original")))
                    .containsExactly(content);
        }
    }

    private static Map<String, Long> rowCounts(JdbcClient jdbc) {
        var counts = new LinkedHashMap<String, Long>();
        for (var table : List.of("documents", "event_keys", "domain_events", "event_deliveries")) {
            counts.put(table, jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
        }
        return counts;
    }

    private static ConfigurableApplicationContext startApplication(Path storageDirectory) {
        return new SpringApplicationBuilder(SecondBrainApplication.class)
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
}
