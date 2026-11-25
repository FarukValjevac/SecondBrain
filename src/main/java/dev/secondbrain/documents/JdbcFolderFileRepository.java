package dev.secondbrain.documents;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One row per file path; each statement is its own short transaction. */
final class JdbcFolderFileRepository {
    private final JdbcClient jdbc;

    JdbcFolderFileRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    enum Outcome {
        IMPORTED,
        REJECTED,
        IGNORED
    }

    record File(
            String path,
            String sha256,
            Outcome outcome,
            Optional<UUID> documentId,
            Optional<UUID> replacedDocumentId,
            boolean documentVisible,
            boolean documentReady) {}

    Optional<File> find(String path) {
        return jdbc.sql(
                        """
                        SELECT f.relative_path, f.content_sha256, f.outcome, f.document_id, f.replaced_document_id,
                               (d.document_id IS NOT NULL AND d.deleted_at IS NULL) AS visible,
                               (d.active_indexing_version IS NOT NULL AND d.deleted_at IS NULL) AS ready
                        FROM folder_files f LEFT JOIN documents d ON d.document_id = f.document_id
                        WHERE f.relative_path = :path
                        """)
                .withQueryTimeout(5)
                .param("path", path)
                .query(
                        (row, index) ->
                                new File(
                                        row.getString("relative_path"),
                                        row.getString("content_sha256"),
                                        Outcome.valueOf(row.getString("outcome")),
                                        Optional.ofNullable(
                                                row.getObject("document_id", UUID.class)),
                                        Optional.ofNullable(
                                                row.getObject("replaced_document_id", UUID.class)),
                                        row.getBoolean("visible"),
                                        row.getBoolean("ready")))
                .optional();
    }

    void save(
            String path,
            String sha256,
            Outcome outcome,
            Optional<UUID> document,
            Optional<UUID> replaced,
            Instant seenAt) {
        jdbc.sql(
                        """
                INSERT INTO folder_files(relative_path, content_sha256, outcome, document_id, replaced_document_id, seen_at)
                VALUES (:path, :hash, :outcome, :document, :replaced, :seen)
                ON CONFLICT (relative_path) DO UPDATE SET content_sha256 = :hash, outcome = :outcome,
                    document_id = :document, replaced_document_id = :replaced, seen_at = :seen
                """)
                .withQueryTimeout(5)
                .param("path", path)
                .param("hash", sha256)
                .param("outcome", outcome.name())
                .param("document", document.orElse(null))
                .param("replaced", replaced.orElse(null))
                .param("seen", Timestamp.from(seenAt))
                .update();
    }

    void touch(String path, Instant seenAt) {
        jdbc.sql("UPDATE folder_files SET seen_at = :seen WHERE relative_path = :path")
                .withQueryTimeout(5)
                .param("path", path)
                .param("seen", Timestamp.from(seenAt))
                .update();
    }

    void clearReplaced(String path) {
        jdbc.sql("UPDATE folder_files SET replaced_document_id = NULL WHERE relative_path = :path")
                .withQueryTimeout(5)
                .param("path", path)
                .update();
    }

    /** Files not seen since the scan started, with their visible document if any. */
    List<File> missingSince(Instant scanStarted) {
        return jdbc.sql(
                        """
                        SELECT f.relative_path, f.content_sha256, f.outcome, f.document_id, f.replaced_document_id,
                               (d.document_id IS NOT NULL AND d.deleted_at IS NULL) AS visible, false AS ready
                        FROM folder_files f LEFT JOIN documents d ON d.document_id = f.document_id
                        WHERE f.seen_at < :started
                        """)
                .withQueryTimeout(5)
                .param("started", Timestamp.from(scanStarted))
                .query(
                        (row, index) ->
                                new File(
                                        row.getString("relative_path"),
                                        row.getString("content_sha256"),
                                        Outcome.valueOf(row.getString("outcome")),
                                        Optional.ofNullable(
                                                row.getObject("document_id", UUID.class)),
                                        Optional.ofNullable(
                                                row.getObject("replaced_document_id", UUID.class)),
                                        row.getBoolean("visible"),
                                        false))
                .list();
    }

    void remove(String path) {
        jdbc.sql("DELETE FROM folder_files WHERE relative_path = :path")
                .withQueryTimeout(5)
                .param("path", path)
                .update();
    }

    int tracked() {
        return jdbc.sql("SELECT count(*) FROM folder_files WHERE outcome = 'IMPORTED'")
                .withQueryTimeout(5)
                .query(Integer.class)
                .single();
    }
}
