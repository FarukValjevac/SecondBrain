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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Testcontainers
class DatabaseMigrationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @Test
    void upgradesExistingTextAndPreservesDataOnApplicationRestart() {
        MigrationInfo firstMigration;
        UUID storageKey = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        var legacyDataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        // Populate the previous schema before application startup applies the page migration.
        Flyway.configure().dataSource(legacyDataSource).target("6").load().migrate();
        var legacyJdbc = JdbcClient.create(legacyDataSource);
        insertDocument(
                legacyJdbc, documentId, storageKey, "notes.md", "MARKDOWN", "a".repeat(64), 42);
        insertExtraction(legacyJdbc, documentId, 1);
        insertTextLine(legacyJdbc, documentId, 1, 3, "Café ☕");
        insertTextLine(legacyJdbc, documentId, 1, 1, "# Étude");
        insertTextLine(legacyJdbc, documentId, 1, 2, "");
        insertExtraction(legacyJdbc, documentId, 2);
        insertTextLine(legacyJdbc, documentId, 2, 1, "Another version");

        try (var context = startApplication()) {
            var jdbc = context.getBean(JdbcClient.class);
            var flyway = context.getBean(Flyway.class);

            Assertions.assertThat(flyway.info().applied()).hasSize(11);
            firstMigration = flyway.info().current();
            Assertions.assertThat(firstMigration.getVersion().getVersion()).isEqualTo("11");
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

            Assertions.assertThat(
                            jdbc.sql(
                                            "SELECT extraction_state FROM documents WHERE document_id = :id")
                                    .param("id", documentId)
                                    .query(String.class)
                                    .single())
                    .isEqualTo("QUEUED");

            assertExtractedLines(jdbc, documentId);
            Assertions.assertThat(
                            jdbc.sql(
                                            """
                            SELECT count(*) FROM documents
                            WHERE document_id = :id AND desired_indexing_version IS NULL
                            """)
                                    .param("id", documentId)
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(1);
            Assertions.assertThat(
                            jdbc.sql("SELECT count(*) FROM document_index_versions")
                                    .query(Integer.class)
                                    .single())
                    .isZero();
            Assertions.assertThat(
                            jdbc.sql("SELECT count(*) FROM document_passages")
                                    .query(Integer.class)
                                    .single())
                    .isZero();
            Assertions.assertThat(
                            jdbc.sql("SELECT count(*) FROM document_embeddings")
                                    .query(Integer.class)
                                    .single())
                    .isZero();
            assertExtractionConstraints(jdbc, documentId);

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
                                            "notes.exe",
                                            "UNKNOWN",
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

            Assertions.assertThat(flyway.info().applied()).hasSize(11);
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
                    .isEqualTo(11);
            assertExtractedLines(jdbc, documentId);
            Assertions.assertThat(
                            jdbc.sql(
                                            """
                                    SELECT extracted_at FROM document_extractions
                                    WHERE document_id = :id AND processing_version = 1
                                    """)
                                    .param("id", documentId)
                                    .query(Timestamp.class)
                                    .single()
                                    .toInstant())
                    .isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));

            jdbc.sql("DELETE FROM documents WHERE document_id = :id")
                    .param("id", documentId)
                    .update();
            Assertions.assertThat(readTextLines(jdbc, documentId, 1)).isEmpty();
            Assertions.assertThat(readTextLines(jdbc, documentId, 2)).isEmpty();
            Assertions.assertThat(
                            jdbc.sql(
                                            "SELECT count(*) FROM document_extractions WHERE document_id = :id")
                                    .param("id", documentId)
                                    .query(Integer.class)
                                    .single())
                    .isZero();
        }
    }

    private static void insertExtraction(JdbcClient jdbc, UUID documentId, long version) {
        jdbc.sql(
                        """
                INSERT INTO document_extractions (document_id, processing_version, extracted_at)
                VALUES (:id, :version, :extractedAt)
                """)
                .param("id", documentId)
                .param("version", version)
                .param("extractedAt", Timestamp.from(Instant.parse("2026-01-01T00:00:00.123456Z")))
                .update();
    }

    private static void insertTextLine(
            JdbcClient jdbc, UUID documentId, long version, int lineNumber, String text) {
        jdbc.sql(
                        """
                INSERT INTO document_text_lines
                    (document_id, processing_version, line_number, line_text)
                VALUES (:id, :version, :number, :text)
                """)
                .param("id", documentId)
                .param("version", version)
                .param("number", lineNumber)
                .param("text", text)
                .update();
    }

    private static List<Map.Entry<Integer, String>> readTextLines(
            JdbcClient jdbc, UUID documentId, long version) {
        return jdbc.sql(
                        """
                        SELECT line_number, line_text FROM document_text_lines
                        WHERE document_id = :id AND processing_version = :version
                        ORDER BY line_number
                        """)
                .param("id", documentId)
                .param("version", version)
                .query(
                        (row, index) ->
                                Map.entry(row.getInt("line_number"), row.getString("line_text")))
                .list();
    }

    private static void assertExtractedLines(JdbcClient jdbc, UUID documentId) {
        Assertions.assertThat(readTextLines(jdbc, documentId, 1))
                .containsExactly(Map.entry(1, "# Étude"), Map.entry(2, ""), Map.entry(3, "Café ☕"));
        Assertions.assertThat(readTextLines(jdbc, documentId, 2))
                .containsExactly(Map.entry(1, "Another version"));
        Assertions.assertThat(
                        jdbc.sql(
                                        """
                                SELECT count(*) FROM document_text_lines
                                WHERE document_id = :id AND page_number IS NULL
                                """)
                                .param("id", documentId)
                                .query(Integer.class)
                                .single())
                .isEqualTo(4);
    }

    private static void assertExtractionConstraints(JdbcClient jdbc, UUID documentId) {
        Assertions.assertThatThrownBy(() -> insertExtraction(jdbc, documentId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        Assertions.assertThatThrownBy(() -> insertExtraction(jdbc, UUID.randomUUID(), 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        Assertions.assertThatThrownBy(() -> insertExtraction(jdbc, documentId, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        Assertions.assertThatThrownBy(
                        () -> insertTextLine(jdbc, documentId, 99, 1, "Orphan version"))
                .isInstanceOf(DataIntegrityViolationException.class);
        Assertions.assertThatThrownBy(
                        () -> insertTextLine(jdbc, documentId, 1, 0, "Invalid location"))
                .isInstanceOf(DataIntegrityViolationException.class);
        Assertions.assertThatThrownBy(
                        () -> insertTextLine(jdbc, documentId, 1, 1, "Duplicate line"))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (var lineBreak : List.of("\n", "\r")) {
            Assertions.assertThatThrownBy(
                            () ->
                                    insertTextLine(
                                            jdbc, documentId, 1, 4, "Two" + lineBreak + "lines"))
                    .isInstanceOf(DataIntegrityViolationException.class);
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
                        "--secondbrain.documents.cleanup.enabled=false",
                        "--secondbrain.documents.indexing.enabled=false",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword());
    }
}
