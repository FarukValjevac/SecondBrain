package dev.secondbrain.study;

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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Real PostgreSQL: visibility, single grading and removal with cited documents. */
class QuizIT {
    private static final TestDatabase POSTGRES = TestDatabase.create();
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static JdbcClient jdbc;
    private static JdbcQuizRepository quizzes;

    @BeforeAll
    static void migrate() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = JdbcClient.create(source);
        quizzes = new JdbcQuizRepository(jdbc, new JdbcTransactionManager(source));
    }

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE documents, quizzes CASCADE").update();
    }

    @Test
    void storesGradesOnceAndHidesTheQuizWhenADocumentIsDeleted() {
        var first = document();
        var second = document();
        var quiz = UUID.randomUUID();
        assertThat(quizzes.insert(quiz, List.of(question(1, first), question(2, second)), NOW))
                .isTrue();
        var correct = new QuizParser.Grade(QuizParser.Verdict.CORRECT, "Yes.");
        assertThat(quizzes.grade(quiz, 1, "Yes", correct)).isTrue();
        assertThat(quizzes.grade(quiz, 1, "Changed", correct)).isFalse();
        assertThat(quizzes.find(quiz).orElseThrow().questions().getFirst().answer())
                .contains("Yes");
        jdbc.sql("UPDATE documents SET deleted_at = updated_at WHERE document_id = :id")
                .param("id", second)
                .update();
        assertThat(quizzes.find(quiz)).isEmpty();
        jdbc.sql("DELETE FROM documents WHERE document_id = :id").param("id", second).update();
        assertThat(jdbc.sql("SELECT count(*) FROM quizzes").query(Integer.class).single()).isZero();
    }

    @Test
    void aQuizCitingAnUnknownDocumentIsNotStored() {
        assertThat(quizzes.insert(UUID.randomUUID(), List.of(question(1, UUID.randomUUID())), NOW))
                .isFalse();
    }

    private static Quiz.Question question(int position, UUID document) {
        return new Quiz.Question(
                position,
                document,
                UUID.randomUUID(),
                "Question " + position + "?",
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
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
                .param("at", Timestamp.from(NOW))
                .update();
        return id;
    }
}
