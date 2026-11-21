package dev.secondbrain.questions;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.TestDatabase;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Real PostgreSQL: visibility checks, export filtering and removal with cited documents. */
class AnswerFeedbackIT {
    private static final TestDatabase POSTGRES = TestDatabase.create();
    private static JdbcClient jdbc;
    private static AnswerFeedbackService feedback;

    @BeforeAll
    static void migrate() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = JdbcClient.create(source);
        feedback =
                new AnswerFeedbackService(
                        new JdbcAnswerFeedbackRepository(jdbc, new JdbcTransactionManager(source)),
                        Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC),
                        Optional.empty());
    }

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE documents, answer_feedback CASCADE").update();
    }

    @Test
    void storesFeedbackForVisibleSourcesAndExportsIt() {
        var document = document();
        assertThat(rate(document)).isTrue();
        assertThat(feedback.export())
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.helpful()).isFalse();
                            assertThat(item.reason()).contains(AnswerFeedback.Reason.WRONG);
                            assertThat(item.sources())
                                    .extracting(AnswerFeedback.Source::documentId)
                                    .containsExactly(document);
                        });
    }

    @Test
    void deletionHidesFeedbackAtOnceAndCleanupRemovesIt() {
        var document = document();
        assertThat(rate(document)).isTrue();
        jdbc.sql("UPDATE documents SET deleted_at = updated_at WHERE document_id = :id")
                .param("id", document)
                .update();
        // Marked deleted: no new feedback, and existing feedback leaves the export.
        assertThat(rate(document)).isFalse();
        assertThat(feedback.export()).isEmpty();
        assertThat(count()).isEqualTo(1);
        // Cleanup removes the row; the stored answer text goes with it.
        jdbc.sql("DELETE FROM documents WHERE document_id = :id").param("id", document).update();
        assertThat(count()).isZero();
    }

    @Test
    void anUnknownDocumentIsNeverStored() {
        assertThat(rate(UUID.randomUUID())).isFalse();
        assertThat(count()).isZero();
    }

    private boolean rate(UUID document) {
        return feedback.record(
                false,
                Optional.of(AnswerFeedback.Reason.WRONG),
                "Does ArrayList allow duplicates?",
                "Yes [1].",
                List.of(new AnswerFeedback.Source(document, UUID.randomUUID())));
    }

    private static int count() {
        return jdbc.sql("SELECT count(*) FROM answer_feedback").query(Integer.class).single();
    }

    private static UUID document() {
        var id = UUID.randomUUID();
        jdbc.sql(
                        """
                INSERT INTO documents(document_id, original_name, document_type, storage_key, content_sha256,
                    original_size_bytes, processing_version, extraction_state, created_at, updated_at)
                VALUES (:id, 'notes.md', 'MARKDOWN', :storage, :hash, 10, 1, 'EXTRACTED', :at, :at)
                """)
                .param("id", id)
                .param("storage", UUID.randomUUID())
                .param("hash", "a".repeat(64))
                .param("at", Timestamp.from(Instant.parse("2026-11-21T09:00:00Z")))
                .update();
        return id;
    }
}
