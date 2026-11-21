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

/** A quiz is visible only while every document it quotes is visible. */
final class JdbcQuizRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    JdbcQuizRepository(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(transactions);
        this.transactions.setTimeout(5);
    }

    boolean insert(UUID quizId, List<Quiz.Question> questions, Instant at) {
        return Boolean.TRUE.equals(
                transactions.execute(
                        status -> {
                            var documents =
                                    questions.stream()
                                            .map(Quiz.Question::documentId)
                                            .distinct()
                                            .toList();
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
                            jdbc.sql("INSERT INTO quizzes(quiz_id, created_at) VALUES (:id, :at)")
                                    .param("id", quizId)
                                    .param("at", Timestamp.from(at))
                                    .update();
                            for (var question : questions)
                                jdbc.sql(
                                                """
                                        INSERT INTO quiz_questions(quiz_id, position, document_id, passage_id, question)
                                        VALUES (:id, :position, :document, :passage, :question)
                                        """)
                                        .param("id", quizId)
                                        .param("position", question.position())
                                        .param("document", question.documentId())
                                        .param("passage", question.passageId())
                                        .param("question", question.text())
                                        .update();
                            return true;
                        }));
    }

    Optional<Quiz> find(UUID quizId) {
        var created =
                jdbc.sql(
                                """
                        SELECT q.created_at FROM quizzes q WHERE q.quiz_id = :id AND NOT EXISTS (
                            SELECT 1 FROM quiz_questions x JOIN documents d ON d.document_id = x.document_id
                            WHERE x.quiz_id = q.quiz_id AND d.deleted_at IS NOT NULL)
                        """)
                        .param("id", quizId)
                        .query(Timestamp.class)
                        .optional();
        if (created.isEmpty()) return Optional.empty();
        var questions =
                jdbc.sql(
                                """
                        SELECT position, document_id, passage_id, question, answer, verdict, explanation
                        FROM quiz_questions WHERE quiz_id = :id ORDER BY position
                        """)
                        .param("id", quizId)
                        .query(
                                (row, index) ->
                                        new Quiz.Question(
                                                row.getInt("position"),
                                                row.getObject("document_id", UUID.class),
                                                row.getObject("passage_id", UUID.class),
                                                row.getString("question"),
                                                Optional.ofNullable(row.getString("answer")),
                                                QuizParser.verdict(row.getString("verdict")),
                                                Optional.ofNullable(row.getString("explanation"))))
                        .list();
        return Optional.of(new Quiz(quizId, created.orElseThrow().toInstant(), questions));
    }

    /** Saves a grade once; a question that already has one keeps it. */
    boolean grade(UUID quizId, int position, String answer, QuizParser.Grade grade) {
        return jdbc.sql(
                                """
                        UPDATE quiz_questions SET answer = :answer, verdict = :verdict, explanation = :explanation
                        WHERE quiz_id = :id AND position = :position AND verdict IS NULL
                        """)
                        .param("id", quizId)
                        .param("position", position)
                        .param("answer", answer)
                        .param("verdict", grade.verdict().name())
                        .param("explanation", grade.explanation())
                        .update()
                == 1;
    }
}
