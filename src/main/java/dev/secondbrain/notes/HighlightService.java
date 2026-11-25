package dev.secondbrain.notes;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Highlights and notes on the current extraction of visible documents. The quote is taken by the
 * caller from the stored lines, never from the browser. Nothing here is logged.
 */
public final class HighlightService {
    private static final String SELECT =
            """
            SELECT h.highlight_id, h.document_id, d.original_name, h.start_line, h.end_line,
                   h.quote, h.note, h.color, h.updated_at
            FROM document_highlights h JOIN documents d ON d.document_id = h.document_id
              AND d.processing_version = h.processing_version AND d.deleted_at IS NULL
            """;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    HighlightService(JdbcClient jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        transactions = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transactions.setTimeout(5);
    }

    /** Highlights of one document's current text, in reading order. */
    public List<Highlight> forDocument(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId");
        return Objects.requireNonNull(
                transactions.execute(
                        status ->
                                jdbc.sql(
                                                SELECT
                                                        + " WHERE h.document_id = :id"
                                                        + " ORDER BY h.start_line, h.created_at")
                                        .withQueryTimeout(5)
                                        .param("id", documentId)
                                        .query((row, index) -> highlight(row))
                                        .list()));
    }

    /** Newest first across all documents, optionally filtered by text in the quote or note. */
    public List<Highlight> recent(String filter, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid limit");
        var text = filter == null ? "" : filter.strip();
        if (text.length() > 200) text = text.substring(0, 200);
        var pattern =
                "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return Objects.requireNonNull(
                transactions.execute(
                        status ->
                                jdbc.sql(
                                                SELECT
                                                        + " WHERE (h.quote ILIKE :pattern OR h.note ILIKE :pattern"
                                                        + " OR d.original_name ILIKE :pattern)"
                                                        + " ORDER BY h.updated_at DESC, h.highlight_id LIMIT :limit")
                                        .withQueryTimeout(5)
                                        .param("pattern", pattern)
                                        .param("limit", limit)
                                        .query((row, index) -> highlight(row))
                                        .list()));
    }

    /**
     * Saves a highlight on lines {@code start..end} of the given extraction. Returns empty when the
     * document is gone or was reprocessed meanwhile.
     */
    public Optional<UUID> add(
            UUID documentId,
            long processingVersion,
            int start,
            int end,
            String quote,
            String note,
            Highlight.Color color) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(color, "color");
        if (start < 1 || end < start || end - start >= Highlight.MAXIMUM_LINES)
            throw new IllegalArgumentException("Invalid line range");
        var cleanQuote =
                clip(Objects.requireNonNull(quote, "quote").strip(), Highlight.MAXIMUM_QUOTE);
        if (cleanQuote.isEmpty()) throw new IllegalArgumentException("Empty highlight");
        var cleanNote = note(note);
        return Objects.requireNonNull(
                transactions.execute(
                        status -> {
                            var current =
                                    jdbc.sql(
                                                    "SELECT 1 FROM documents WHERE document_id = :id"
                                                            + " AND processing_version = :version"
                                                            + " AND deleted_at IS NULL FOR KEY SHARE")
                                            .withQueryTimeout(5)
                                            .param("id", documentId)
                                            .param("version", processingVersion)
                                            .query(Integer.class)
                                            .optional();
                            if (current.isEmpty()) return Optional.<UUID>empty();
                            var id = UUID.randomUUID();
                            var now = Timestamp.from(clock.instant());
                            jdbc.sql(
                                            """
                                            INSERT INTO document_highlights (highlight_id, document_id,
                                                processing_version, start_line, end_line, quote, note,
                                                color, created_at, updated_at)
                                            VALUES (:id, :document, :version, :start, :end, :quote,
                                                :note, :color, :now, :now)
                                            """)
                                    .withQueryTimeout(5)
                                    .param("id", id)
                                    .param("document", documentId)
                                    .param("version", processingVersion)
                                    .param("start", start)
                                    .param("end", end)
                                    .param("quote", cleanQuote)
                                    .param("note", cleanNote)
                                    .param("color", color.name())
                                    .param("now", now)
                                    .update();
                            return Optional.of(id);
                        }));
    }

    /** Returns the document of the updated highlight, or empty when it no longer exists. */
    public Optional<UUID> update(UUID highlightId, String note, Highlight.Color color) {
        Objects.requireNonNull(highlightId, "highlightId");
        Objects.requireNonNull(color, "color");
        var cleanNote = note(note);
        return Objects.requireNonNull(
                transactions.execute(
                        status ->
                                jdbc.sql(
                                                """
                                                UPDATE document_highlights SET note = :note, color = :color,
                                                    updated_at = :now WHERE highlight_id = :id
                                                RETURNING document_id
                                                """)
                                        .withQueryTimeout(5)
                                        .param("id", highlightId)
                                        .param("note", cleanNote)
                                        .param("color", color.name())
                                        .param("now", Timestamp.from(clock.instant()))
                                        .query(UUID.class)
                                        .optional()));
    }

    /** Returns the document of the deleted highlight, or empty when it was already gone. */
    public Optional<UUID> delete(UUID highlightId) {
        Objects.requireNonNull(highlightId, "highlightId");
        return Objects.requireNonNull(
                transactions.execute(
                        status ->
                                jdbc.sql(
                                                "DELETE FROM document_highlights WHERE highlight_id = :id"
                                                        + " RETURNING document_id")
                                        .withQueryTimeout(5)
                                        .param("id", highlightId)
                                        .query(UUID.class)
                                        .optional()));
    }

    private static String note(String note) {
        var clean = note == null ? "" : note.strip();
        if (clean.length() > Highlight.MAXIMUM_NOTE || clean.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Note too long");
        return clean;
    }

    private static String clip(String text, int maximum) {
        return text.length() <= maximum ? text : text.substring(0, maximum - 1) + "…";
    }

    private static Highlight highlight(ResultSet row) throws SQLException {
        return new Highlight(
                row.getObject("highlight_id", UUID.class),
                row.getObject("document_id", UUID.class),
                row.getString("original_name"),
                row.getInt("start_line"),
                row.getInt("end_line"),
                row.getString("quote"),
                row.getString("note"),
                Highlight.Color.valueOf(row.getString("color")),
                row.getTimestamp("updated_at").toInstant());
    }
}
