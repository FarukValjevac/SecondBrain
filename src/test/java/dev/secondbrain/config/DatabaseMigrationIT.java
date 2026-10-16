package dev.secondbrain.config;

import dev.secondbrain.SecondBrainApplication;

import org.assertj.core.api.Assertions;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Testcontainers
class DatabaseMigrationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @Test
    void migratesAnEmptyDatabaseAndPreservesDataOnApplicationRestart() {
        MigrationInfo firstMigration;
        UUID storageKey = UUID.randomUUID();
        try (var context = startApplication()) {
            var jdbc = context.getBean(JdbcClient.class);
            var flyway = context.getBean(Flyway.class);

            Assertions.assertThat(flyway.info().applied()).hasSize(3);
            firstMigration = flyway.info().current();
            Assertions.assertThat(firstMigration.getVersion().getVersion()).isEqualTo("3");
            Assertions.assertThat(
                            jdbc.sql("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
                                    .query(String.class)
                                    .single())
                    .isEqualTo("0.8.7");
            Assertions.assertThat(
                            jdbc.sql("SELECT '[1,2,3]'::vector <-> '[1,2,4]'::vector")
                                    .query(Double.class)
                                    .single())
                    .isEqualTo(1.0);

            var documentId = UUID.randomUUID();
            insertDocument(
                    jdbc, documentId, storageKey, "notes.md", "MARKDOWN", "a".repeat(64), 42);
            Assertions.assertThat(
                            jdbc.sql(
                                            "SELECT extraction_state FROM documents WHERE document_id = :id")
                                    .param("id", documentId)
                                    .query(String.class)
                                    .single())
                    .isEqualTo("QUEUED");

            Assertions.assertThatThrownBy(
                            () ->
                                    insertDocument(
                                            jdbc,
                                            UUID.randomUUID(),
                                            UUID.randomUUID(),
                                            " ",
                                            "TXT",
                                            "b".repeat(64),
                                            1))
                    .isInstanceOf(DataIntegrityViolationException.class);
            Assertions.assertThatThrownBy(
                            () ->
                                    insertDocument(
                                            jdbc,
                                            UUID.randomUUID(),
                                            UUID.randomUUID(),
                                            "notes.pdf",
                                            "PDF",
                                            "b".repeat(64),
                                            1))
                    .isInstanceOf(DataIntegrityViolationException.class);
            Assertions.assertThatThrownBy(
                            () ->
                                    insertDocument(
                                            jdbc,
                                            UUID.randomUUID(),
                                            UUID.randomUUID(),
                                            "notes.txt",
                                            "TXT",
                                            "invalid",
                                            1))
                    .isInstanceOf(DataIntegrityViolationException.class);
            Assertions.assertThatThrownBy(
                            () ->
                                    insertDocument(
                                            jdbc,
                                            UUID.randomUUID(),
                                            UUID.randomUUID(),
                                            "notes.txt",
                                            "TXT",
                                            "b".repeat(64),
                                            0))
                    .isInstanceOf(DataIntegrityViolationException.class);
            Assertions.assertThatThrownBy(
                            () ->
                                    insertDocument(
                                            jdbc,
                                            UUID.randomUUID(),
                                            storageKey,
                                            "another.txt",
                                            "TXT",
                                            "b".repeat(64),
                                            1))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        try (var context = startApplication()) {
            var jdbc = context.getBean(JdbcClient.class);
            var flyway = context.getBean(Flyway.class);

            Assertions.assertThat(flyway.info().applied()).hasSize(3);
            Assertions.assertThat(flyway.info().current().getInstalledOn())
                    .isEqualTo(firstMigration.getInstalledOn());
            Assertions.assertThat(
                            jdbc.sql("SELECT original_name FROM documents WHERE storage_key = :key")
                                    .param("key", storageKey)
                                    .query(String.class)
                                    .single())
                    .isEqualTo("notes.md");
            Assertions.assertThat(
                            jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success")
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(3);
        }
    }

    private static void insertDocument(
            JdbcClient jdbc,
            UUID documentId,
            UUID storageKey,
            String originalName,
            String documentType,
            String hash,
            long size) {
        var now = Instant.parse("2026-01-01T00:00:00Z");
        jdbc.sql(
                        """
                        INSERT INTO documents (
                            document_id, original_name, document_type, storage_key, content_sha256,
                            original_size_bytes, created_at, updated_at
                        ) VALUES (
                            :documentId, :originalName, :documentType, :storageKey, :hash,
                            :size, :createdAt, :updatedAt
                        )
                        """)
                .param("documentId", documentId)
                .param("originalName", originalName)
                .param("documentType", documentType)
                .param("storageKey", storageKey)
                .param("hash", hash)
                .param("size", size)
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
    }

    private static ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.config.import=",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword());
    }
}
