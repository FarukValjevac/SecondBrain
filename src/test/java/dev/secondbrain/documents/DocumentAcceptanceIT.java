package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.SecondBrainApplication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.util.UUID;

@Testcontainers
class DocumentAcceptanceIT {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir private Path storageDirectory;

    @Test
    void commitsDocumentAndDurableAcceptedEventAndKeepsOriginalPrivate() throws Exception {
        var content = "My meeting notes".getBytes(StandardCharsets.UTF_8);
        var correlationId = UUID.randomUUID();
        try (var context = startApplication(storageDirectory)) {
            var service = context.getBean(DocumentAcceptanceService.class);
            var jdbc = context.getBean(JdbcClient.class);

            var document =
                    service.accept(
                            "meeting notes.md",
                            content.length,
                            new ByteArrayInputStream(content),
                            correlationId);

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

    private static ConfigurableApplicationContext startApplication(Path storageDirectory) {
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.config.import=",
                        "--secondbrain.documents.cleanup.enabled=false",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--secondbrain.documents.storage-directory=" + storageDirectory);
    }
}
