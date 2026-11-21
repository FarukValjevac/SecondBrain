package dev.secondbrain.study;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Cards of deleted documents are hidden at once and removed with the document row. */
final class JdbcFlashcardRepository {
    private static final String CARD_COLUMNS =
            "c.card_id, c.document_id, c.passage_id, c.question, c.answer, c.due_at, c.interval_days, c.ease, c.repetitions";
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    JdbcFlashcardRepository(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(transactions);
        this.transactions.setTimeout(5);
    }

    /** Stores the cards only if the document is still visible with the same active generation. */
    boolean insert(UUID documentId, long version, List<Flashcard> cards, Instant at) {
        return Boolean.TRUE.equals(
                transactions.execute(
                        status -> {
                            int current =
                                    jdbc.sql(
                                                    """
                                    SELECT count(*) FROM (SELECT 1 FROM documents WHERE document_id = :id
                                        AND deleted_at IS NULL AND active_indexing_version = :version FOR SHARE) d
                                    """)
                                            .param("id", documentId)
                                            .param("version", version)
                                            .query(Integer.class)
                                            .single();
                            if (current != 1) return false;
                            for (var card : cards)
                                jdbc.sql(
                                                """
                                        INSERT INTO flashcards(card_id, document_id, passage_id, question, answer,
                                            created_at, due_at, interval_days, ease, repetitions)
                                        VALUES (:id, :document, :passage, :question, :answer, :at, :due, :interval, :ease, :repetitions)
                                        """)
                                        .param("id", card.cardId())
                                        .param("document", documentId)
                                        .param("passage", card.passageId())
                                        .param("question", card.question())
                                        .param("answer", card.answer())
                                        .param("at", Timestamp.from(at))
                                        .param("due", Timestamp.from(card.schedule().dueAt()))
                                        .param("interval", card.schedule().intervalDays())
                                        .param("ease", card.schedule().ease())
                                        .param("repetitions", card.schedule().repetitions())
                                        .update();
                            return true;
                        }));
    }

    Optional<Flashcard> nextDue(Instant now) {
        return jdbc.sql(
                        "SELECT "
                                + CARD_COLUMNS
                                + """
                         FROM flashcards c JOIN documents d ON d.document_id = c.document_id
                        WHERE d.deleted_at IS NULL AND c.due_at <= :now
                        ORDER BY c.due_at, c.card_id LIMIT 1
                        """)
                .param("now", Timestamp.from(now))
                .query(JdbcFlashcardRepository::card)
                .optional();
    }

    Optional<Flashcard> find(UUID cardId) {
        return jdbc.sql(
                        "SELECT "
                                + CARD_COLUMNS
                                + """
                         FROM flashcards c JOIN documents d ON d.document_id = c.document_id
                        WHERE d.deleted_at IS NULL AND c.card_id = :id
                        """)
                .param("id", cardId)
                .query(JdbcFlashcardRepository::card)
                .optional();
    }

    int countDue(Instant now) {
        return jdbc.sql(
                        """
                        SELECT count(*) FROM flashcards c JOIN documents d ON d.document_id = c.document_id
                        WHERE d.deleted_at IS NULL AND c.due_at <= :now
                        """)
                .param("now", Timestamp.from(now))
                .query(Integer.class)
                .single();
    }

    /** Applies a review only to a visible card that is still in the state the user saw. */
    boolean reschedule(UUID cardId, ReviewScheduler.Schedule seen, ReviewScheduler.Schedule next) {
        return jdbc.sql(
                                """
                        UPDATE flashcards c SET due_at = :due, interval_days = :interval, ease = :ease,
                            repetitions = :repetitions
                        FROM documents d
                        WHERE c.card_id = :id AND d.document_id = c.document_id AND d.deleted_at IS NULL
                          AND c.repetitions = :seenRepetitions AND c.due_at = :seenDue
                        """)
                        .param("id", cardId)
                        .param("due", Timestamp.from(next.dueAt()))
                        .param("interval", next.intervalDays())
                        .param("ease", next.ease())
                        .param("repetitions", next.repetitions())
                        .param("seenRepetitions", seen.repetitions())
                        .param("seenDue", Timestamp.from(seen.dueAt()))
                        .update()
                == 1;
    }

    private static Flashcard card(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Flashcard(
                row.getObject("card_id", UUID.class),
                row.getObject("document_id", UUID.class),
                row.getObject("passage_id", UUID.class),
                row.getString("question"),
                row.getString("answer"),
                new ReviewScheduler.Schedule(
                        row.getInt("interval_days"),
                        row.getDouble("ease"),
                        row.getInt("repetitions"),
                        row.getTimestamp("due_at").toInstant()));
    }
}
