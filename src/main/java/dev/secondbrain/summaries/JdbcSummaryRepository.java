package dev.secondbrain.summaries;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Summaries are written only inside a fenced event transaction and read for visible documents. */
final class JdbcSummaryRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    JdbcSummaryRepository(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(transactions);
        this.transactions.setTimeout(5);
    }

    /**
     * Joins the caller's transaction. Stores the summary only if the document is still visible and
     * the generation it was built from is still active; otherwise the work is stale.
     */
    boolean save(UUID documentId, long version, String text, List<UUID> passages, Instant at) {
        var current =
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
        jdbc.sql(
                        """
                INSERT INTO document_summaries(document_id, indexing_version, summary, generated_at)
                VALUES (:id, :version, :text, :at)
                ON CONFLICT (document_id) DO UPDATE SET indexing_version = :version,
                    summary = :text, generated_at = :at
                """)
                .param("id", documentId)
                .param("version", version)
                .param("text", text)
                .param("at", Timestamp.from(at))
                .update();
        jdbc.sql("DELETE FROM document_summary_sources WHERE document_id = :id")
                .param("id", documentId)
                .update();
        for (int index = 0; index < passages.size(); index++)
            jdbc.sql(
                            """
                    INSERT INTO document_summary_sources(document_id, position, passage_id)
                    VALUES (:id, :position, :passage)
                    """)
                    .param("id", documentId)
                    .param("position", index + 1)
                    .param("passage", passages.get(index))
                    .update();
        return true;
    }

    /** The summary of the document's active generation, if one is stored and it is visible. */
    Optional<Stored> find(UUID documentId) {
        var summary =
                jdbc.sql(
                                """
                        SELECT s.summary, s.generated_at FROM document_summaries s
                        JOIN documents d ON d.document_id = s.document_id
                        WHERE s.document_id = :id AND d.deleted_at IS NULL
                          AND d.active_indexing_version = s.indexing_version
                        """)
                        .param("id", documentId)
                        .query(
                                (row, index) ->
                                        new Stored(
                                                row.getString("summary"),
                                                row.getTimestamp("generated_at").toInstant(),
                                                List.of()))
                        .optional();
        if (summary.isEmpty()) return Optional.empty();
        var passages =
                jdbc.sql(
                                "SELECT passage_id FROM document_summary_sources WHERE document_id = :id ORDER BY position")
                        .param("id", documentId)
                        .query(UUID.class)
                        .list();
        var stored = summary.orElseThrow();
        return Optional.of(new Stored(stored.text(), stored.generatedAt(), passages));
    }

    /** Next regeneration number for this document, in its own short transaction. */
    long nextRequest(UUID documentId) {
        return Objects.requireNonNull(
                transactions.execute(
                        status ->
                                jdbc.sql(
                                                """
                                        INSERT INTO document_summary_requests(document_id, last_request)
                                        VALUES (:id, 1)
                                        ON CONFLICT (document_id) DO UPDATE
                                            SET last_request = document_summary_requests.last_request + 1
                                        RETURNING last_request
                                        """)
                                        .param("id", documentId)
                                        .query(Long.class)
                                        .single()));
    }

    record Stored(String text, Instant generatedAt, List<UUID> passages) {}
}
