package dev.secondbrain.conversations;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Conversation persistence; callers own transactions. */
final class JdbcConversationRepository {
    private final JdbcClient jdbc;

    JdbcConversationRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    List<Conversation.Summary> recent(int limit) {
        return jdbc.sql(
                        "SELECT conversation_id, title, updated_at FROM conversations"
                                + " ORDER BY updated_at DESC, conversation_id LIMIT :limit")
                .withQueryTimeout(5)
                .param("limit", limit)
                .query((row, index) -> summary(row))
                .list();
    }

    Optional<Conversation.Summary> lock(UUID id) {
        return jdbc.sql(
                        "SELECT conversation_id, title, updated_at FROM conversations"
                                + " WHERE conversation_id = :id FOR UPDATE")
                .withQueryTimeout(5)
                .param("id", id)
                .query((row, index) -> summary(row))
                .optional();
    }

    Optional<Conversation.Summary> find(UUID id) {
        return jdbc.sql(
                        "SELECT conversation_id, title, updated_at FROM conversations"
                                + " WHERE conversation_id = :id")
                .withQueryTimeout(5)
                .param("id", id)
                .query((row, index) -> summary(row))
                .optional();
    }

    List<Conversation.Turn> turns(UUID id) {
        var sources = new HashMap<Integer, List<Conversation.SourceRef>>();
        jdbc.sql(
                        "SELECT position, document_id, passage_id FROM conversation_turn_sources"
                                + " WHERE conversation_id = :id ORDER BY position, source_number")
                .withQueryTimeout(5)
                .param("id", id)
                .query(
                        (ResultSet row) -> {
                            sources.computeIfAbsent(
                                            row.getInt("position"), key -> new ArrayList<>())
                                    .add(
                                            new Conversation.SourceRef(
                                                    row.getObject("document_id", UUID.class),
                                                    row.getObject("passage_id", UUID.class)));
                        });
        return jdbc.sql(
                        "SELECT position, question, scope, outcome, answer, created_at"
                                + " FROM conversation_turns WHERE conversation_id = :id"
                                + " ORDER BY position")
                .withQueryTimeout(5)
                .param("id", id)
                .query(
                        (row, index) ->
                                new Conversation.Turn(
                                        row.getInt("position"),
                                        row.getString("question"),
                                        row.getString("scope"),
                                        Conversation.Outcome.valueOf(row.getString("outcome")),
                                        row.getString("answer"),
                                        sources.getOrDefault(row.getInt("position"), List.of()),
                                        row.getTimestamp("created_at").toInstant()))
                .list();
    }

    Optional<String> lastQuestion(UUID id) {
        return jdbc.sql(
                        "SELECT question FROM conversation_turns WHERE conversation_id = :id"
                                + " ORDER BY position DESC LIMIT 1")
                .withQueryTimeout(5)
                .param("id", id)
                .query(String.class)
                .optional();
    }

    int nextPosition(UUID id) {
        return jdbc.sql(
                        "SELECT COALESCE(max(position), 0) + 1 FROM conversation_turns"
                                + " WHERE conversation_id = :id")
                .withQueryTimeout(5)
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    void insert(UUID id, String title, Instant now) {
        jdbc.sql(
                        "INSERT INTO conversations (conversation_id, title, created_at, updated_at)"
                                + " VALUES (:id, :title, :now, :now)")
                .withQueryTimeout(5)
                .param("id", id)
                .param("title", title)
                .param("now", Timestamp.from(now))
                .update();
    }

    void insertTurn(UUID id, Conversation.Turn turn) {
        jdbc.sql(
                        """
                        INSERT INTO conversation_turns (conversation_id, position, question, scope,
                            outcome, answer, created_at)
                        VALUES (:id, :position, :question, :scope, :outcome, :answer, :created)
                        """)
                .withQueryTimeout(5)
                .param("id", id)
                .param("position", turn.position())
                .param("question", turn.question())
                .param("scope", turn.scope())
                .param("outcome", turn.outcome().name())
                .param("answer", turn.answer())
                .param("created", Timestamp.from(turn.createdAt()))
                .update();
        int number = 1;
        for (var source : turn.sources()) {
            jdbc.sql(
                            """
                            INSERT INTO conversation_turn_sources (conversation_id, position,
                                source_number, document_id, passage_id)
                            VALUES (:id, :position, :number, :document, :passage)
                            """)
                    .withQueryTimeout(5)
                    .param("id", id)
                    .param("position", turn.position())
                    .param("number", number++)
                    .param("document", source.documentId())
                    .param("passage", source.passageId())
                    .update();
        }
        touch(id, turn.createdAt());
    }

    void rename(UUID id, String title) {
        jdbc.sql("UPDATE conversations SET title = :title WHERE conversation_id = :id")
                .withQueryTimeout(5)
                .param("id", id)
                .param("title", title)
                .update();
    }

    private void touch(UUID id, Instant now) {
        jdbc.sql("UPDATE conversations SET updated_at = :now WHERE conversation_id = :id")
                .withQueryTimeout(5)
                .param("id", id)
                .param("now", Timestamp.from(now))
                .update();
    }

    boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM conversations WHERE conversation_id = :id")
                        .withQueryTimeout(5)
                        .param("id", id)
                        .update()
                == 1;
    }

    int deleteAll() {
        return jdbc.sql("DELETE FROM conversations").withQueryTimeout(10).update();
    }

    private static Conversation.Summary summary(ResultSet row) throws SQLException {
        return new Conversation.Summary(
                row.getObject("conversation_id", UUID.class),
                row.getString("title"),
                row.getTimestamp("updated_at").toInstant());
    }
}
