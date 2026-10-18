package dev.secondbrain.documents;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

final class JdbcExtractedTextRepository {
    private static final int LINE_BATCH_SIZE = 100;
    private static final int TRANSACTION_TIMEOUT_SECONDS = 10;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcExtractedTextRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions =
                new TransactionTemplate(
                        Objects.requireNonNull(transactionManager, "transactionManager"));
        // REQUIRED joins the caller's transaction, including its existing timeout.
        transactions.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    }

    /** Inserts one immutable version; an existing version is never overwritten. */
    void insert(ExtractedText text) {
        Objects.requireNonNull(text, "text");
        transactions.executeWithoutResult(
                status -> {
                    jdbc.update(
                            """
                    INSERT INTO document_extractions (document_id, processing_version, extracted_at)
                    VALUES (?, ?, ?)
                    """,
                            statement -> {
                                statement.setObject(1, text.documentId());
                                statement.setLong(2, text.processingVersion());
                                statement.setTimestamp(3, Timestamp.from(text.extractedAt()));
                            });
                    jdbc.batchUpdate(
                            """
                    INSERT INTO document_text_lines
                        (document_id, processing_version, line_number, line_text, page_number)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                            text.lines(),
                            LINE_BATCH_SIZE,
                            (statement, line) -> {
                                statement.setObject(1, text.documentId());
                                statement.setLong(2, text.processingVersion());
                                statement.setInt(3, line.number());
                                statement.setString(4, line.text());
                                if (line.pageNumber().isPresent()) {
                                    statement.setInt(5, line.pageNumber().getAsInt());
                                } else {
                                    statement.setNull(5, Types.INTEGER);
                                }
                            });
                });
    }

    Optional<ExtractedText> findByDocumentVersion(UUID documentId, long processingVersion) {
        Objects.requireNonNull(documentId, "documentId");
        if (processingVersion < 1) {
            throw new IllegalArgumentException("Processing version must be positive");
        }
        return Objects.requireNonNull(
                transactions.execute(
                        status ->
                                jdbc.query(
                                        """
                        SELECT extraction.extracted_at, line.line_number, line.line_text, line.page_number
                        FROM document_extractions extraction
                        LEFT JOIN document_text_lines line
                            ON line.document_id = extraction.document_id
                            AND line.processing_version = extraction.processing_version
                        WHERE extraction.document_id = ? AND extraction.processing_version = ?
                        ORDER BY line.line_number
                        """,
                                        statement -> {
                                            statement.setObject(1, documentId);
                                            statement.setLong(2, processingVersion);
                                        },
                                        rows -> {
                                            return readText(rows, documentId, processingVersion);
                                        })));
    }

    private static Optional<ExtractedText> readText(
            ResultSet rows, UUID documentId, long processingVersion) throws SQLException {
        if (!rows.next()) {
            return Optional.empty();
        }
        var extractedAt = rows.getTimestamp("extracted_at").toInstant();
        var lines = new ArrayList<ExtractedText.Line>();
        do {
            var number = rows.getInt("line_number");
            if (rows.wasNull()) {
                throw new IllegalStateException("Stored extraction has no text lines");
            }
            var pageNumber = rows.getInt("page_number");
            var page = rows.wasNull() ? OptionalInt.empty() : OptionalInt.of(pageNumber);
            lines.add(new ExtractedText.Line(number, rows.getString("line_text"), page));
        } while (rows.next());
        return Optional.of(new ExtractedText(documentId, processingVersion, extractedAt, lines));
    }
}
