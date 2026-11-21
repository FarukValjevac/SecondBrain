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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Real PostgreSQL: generation fence, visibility, review guard and removal with documents. */
class FlashcardIT {
    private static final TestDatabase POSTGRES = TestDatabase.create();
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static JdbcClient jdbc;
    private static JdbcFlashcardRepository cards;

    @BeforeAll
    static void migrate() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = JdbcClient.create(source);
        cards = new JdbcFlashcardRepository(jdbc, new JdbcTransactionManager(source));
    }

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE documents CASCADE").update();
    }

    @Test
    void storesCardsOnlyForTheActiveGenerationOfAVisibleDocument() {
        var document = document(3);
        assertThat(cards.insert(document, 2, List.of(card(document)), NOW)).isFalse();
        assertThat(cards.insert(document, 3, List.of(card(document)), NOW)).isTrue();
        assertThat(cards.countDue(NOW)).isEqualTo(1);
        assertThat(cards.nextDue(NOW)).isPresent();
        assertThat(cards.nextDue(NOW.minusSeconds(1))).isEmpty();
    }

    @Test
    void aReviewAppliesOnceEvenWhenTwoTabsSubmitIt() {
        var document = document(1);
        var card = card(document);
        cards.insert(document, 1, List.of(card), NOW);
        var next = ReviewScheduler.next(card.schedule(), ReviewScheduler.Grade.GOOD, NOW);
        assertThat(cards.reschedule(card.cardId(), card.schedule(), next)).isTrue();
        assertThat(cards.reschedule(card.cardId(), card.schedule(), next)).isFalse();
        assertThat(cards.find(card.cardId()).orElseThrow().schedule().dueAt())
                .isEqualTo(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    void deletionHidesCardsAtOnceAndCleanupRemovesThem() {
        var document = document(1);
        var card = card(document);
        cards.insert(document, 1, List.of(card), NOW);
        jdbc.sql("UPDATE documents SET deleted_at = updated_at WHERE document_id = :id")
                .param("id", document)
                .update();
        assertThat(cards.countDue(NOW)).isZero();
        assertThat(cards.find(card.cardId())).isEmpty();
        assertThat(
                        cards.reschedule(
                                card.cardId(),
                                card.schedule(),
                                ReviewScheduler.next(
                                        card.schedule(), ReviewScheduler.Grade.GOOD, NOW)))
                .isFalse();
        jdbc.sql("DELETE FROM documents WHERE document_id = :id").param("id", document).update();
        assertThat(jdbc.sql("SELECT count(*) FROM flashcards").query(Integer.class).single())
                .isZero();
    }

    private static Flashcard card(UUID document) {
        return new Flashcard(
                UUID.randomUUID(),
                document,
                UUID.randomUUID(),
                "Does ArrayList allow duplicates?",
                "Yes.",
                ReviewScheduler.Schedule.initial(NOW));
    }

    /** A visible document whose active index generation is the given number. */
    private static UUID document(long activeGeneration) {
        var id = UUID.randomUUID();
        var at = Timestamp.from(NOW.minusSeconds(3600));
        jdbc.sql(
                        """
                INSERT INTO documents(document_id, original_name, document_type, storage_key, content_sha256,
                    original_size_bytes, processing_version, extraction_state, created_at, updated_at)
                VALUES (:id, 'notes.md', 'MARKDOWN', :storage, :hash, 10, 1, 'EXTRACTED', :at, :at)
                """)
                .param("id", id)
                .param("storage", UUID.randomUUID())
                .param("hash", "a".repeat(64))
                .param("at", at)
                .update();
        jdbc.sql(
                        "INSERT INTO document_extractions(document_id, processing_version, extracted_at) VALUES (:id, 1, :at)")
                .param("id", id)
                .param("at", at)
                .update();
        jdbc.sql(
                        """
                INSERT INTO document_index_versions(document_id, indexing_version, extraction_version, created_at,
                    passage_configuration_version, passage_count, embedding_model_id, embedding_dimensions)
                VALUES (:id, :generation, 1, :at, 1, 1, '@cf/baai/bge-m3', 1024)
                """)
                .param("id", id)
                .param("generation", activeGeneration)
                .param("at", at)
                .update();
        jdbc.sql(
                        "UPDATE documents SET active_indexing_version = :generation WHERE document_id = :id")
                .param("id", id)
                .param("generation", activeGeneration)
                .update();
        return id;
    }
}
