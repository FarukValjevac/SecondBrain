package dev.secondbrain.documents;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Recognised page text, saved page by page. Callers own transactions. Never log the text. */
final class JdbcOcrPageRepository {
    private final JdbcClient jdbc;

    JdbcOcrPageRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Pages recognised so far for this processing version, by page number. */
    Map<Integer, String> recognised(UUID documentId, long version) {
        var pages = new TreeMap<Integer, String>();
        jdbc.sql(
                        "SELECT page_number, recognised_text FROM document_ocr_pages"
                                + " WHERE document_id = :id AND processing_version = :version")
                .withQueryTimeout(5)
                .param("id", documentId)
                .param("version", version)
                .query(
                        (java.sql.ResultSet row) -> {
                            pages.put(row.getInt("page_number"), row.getString("recognised_text"));
                        });
        return pages;
    }

    void save(UUID documentId, long version, int page, int pageCount, String text, Instant now) {
        jdbc.sql(
                        """
                        INSERT INTO document_ocr_pages (document_id, processing_version, page_number,
                            page_count, recognised_text, recognised_at)
                        VALUES (:id, :version, :page, :count, :text, :now)
                        ON CONFLICT (document_id, processing_version, page_number) DO NOTHING
                        """)
                .withQueryTimeout(5)
                .param("id", documentId)
                .param("version", version)
                .param("page", page)
                .param("count", pageCount)
                .param("text", text)
                .param("now", Timestamp.from(now))
                .update();
    }

    void delete(UUID documentId) {
        jdbc.sql("DELETE FROM document_ocr_pages WHERE document_id = :id")
                .withQueryTimeout(5)
                .param("id", documentId)
                .update();
    }
}
