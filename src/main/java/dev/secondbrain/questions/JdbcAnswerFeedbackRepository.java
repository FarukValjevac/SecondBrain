package dev.secondbrain.questions;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Feedback rows and their cited sources commit together. */
final class JdbcAnswerFeedbackRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    JdbcAnswerFeedbackRepository(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(transactions);
        this.transactions.setTimeout(5);
    }

    /**
     * Stores the feedback only if every cited document is still visible; returns whether it did.
     */
    boolean insert(AnswerFeedback feedback) {
        return Boolean.TRUE.equals(
                transactions.execute(
                        status -> {
                            var documents =
                                    feedback.sources().stream()
                                            .map(AnswerFeedback.Source::documentId)
                                            .distinct()
                                            .toList();
                            // Lock the cited documents so a concurrent deletion waits or wins.
                            int visible =
                                    jdbc.sql(
                                                    """
                                    SELECT count(*) FROM (SELECT 1 FROM documents
                                        WHERE document_id IN (:ids) AND deleted_at IS NULL FOR SHARE) d
                                    """)
                                            .param("ids", documents)
                                            .query(Integer.class)
                                            .single();
                            if (visible != documents.size()) return false;
                            jdbc.sql(
                                            """
                                    INSERT INTO answer_feedback(feedback_id, helpful, reason, question, answer, created_at)
                                    VALUES (:id, :helpful, :reason, :question, :answer, :at)
                                    """)
                                    .param("id", feedback.feedbackId())
                                    .param("helpful", feedback.helpful())
                                    .param("reason", feedback.reason().map(Enum::name).orElse(null))
                                    .param("question", feedback.question())
                                    .param("answer", feedback.answer())
                                    .param("at", Timestamp.from(feedback.createdAt()))
                                    .update();
                            for (int index = 0; index < feedback.sources().size(); index++) {
                                var source = feedback.sources().get(index);
                                jdbc.sql(
                                                """
                                        INSERT INTO answer_feedback_sources(feedback_id, position, document_id, passage_id)
                                        VALUES (:id, :position, :document, :passage)
                                        """)
                                        .param("id", feedback.feedbackId())
                                        .param("position", index + 1)
                                        .param("document", source.documentId())
                                        .param("passage", source.passageId())
                                        .update();
                            }
                            return true;
                        }));
    }

    /** Oldest first; feedback citing a document that is marked deleted is left out. */
    List<AnswerFeedback> exportable() {
        var rows =
                jdbc.sql(
                                """
                        SELECT f.feedback_id, f.helpful, f.reason, f.question, f.answer, f.created_at,
                               s.document_id, s.passage_id
                        FROM answer_feedback f JOIN answer_feedback_sources s ON s.feedback_id = f.feedback_id
                        WHERE NOT EXISTS (
                            SELECT 1 FROM answer_feedback_sources x JOIN documents d ON d.document_id = x.document_id
                            WHERE x.feedback_id = f.feedback_id AND d.deleted_at IS NOT NULL)
                        ORDER BY f.created_at, f.feedback_id, s.position
                        """)
                        .query(
                                (row, index) ->
                                        new Row(
                                                row.getObject("feedback_id", UUID.class),
                                                row.getBoolean("helpful"),
                                                row.getString("reason"),
                                                row.getString("question"),
                                                row.getString("answer"),
                                                row.getTimestamp("created_at").toInstant(),
                                                new AnswerFeedback.Source(
                                                        row.getObject("document_id", UUID.class),
                                                        row.getObject("passage_id", UUID.class))))
                        .list();
        var grouped = new LinkedHashMap<UUID, List<Row>>();
        for (var row : rows) grouped.computeIfAbsent(row.id(), id -> new ArrayList<>()).add(row);
        var result = new ArrayList<AnswerFeedback>(grouped.size());
        for (var group : grouped.values()) {
            var first = group.getFirst();
            result.add(
                    new AnswerFeedback(
                            first.id(),
                            first.helpful(),
                            Optional.ofNullable(first.reason()).map(AnswerFeedback.Reason::valueOf),
                            first.question(),
                            first.answer(),
                            group.stream().map(Row::source).toList(),
                            first.createdAt()));
        }
        return List.copyOf(result);
    }

    private record Row(
            UUID id,
            boolean helpful,
            String reason,
            String question,
            String answer,
            java.time.Instant createdAt,
            AnswerFeedback.Source source) {}
}
