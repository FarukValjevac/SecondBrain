package dev.secondbrain.imports;

import dev.secondbrain.documents.DocumentAcceptanceService;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Imports one web page as a document through the normal upload path, recording its source URL. The
 * page text is reference data like any upload; nothing in it is executed or followed.
 */
public final class WebPageImportService {
    private final PageFetcher fetcher;
    private final DocumentAcceptanceService acceptance;
    private final JdbcClient jdbc;

    WebPageImportService(
            PageFetcher fetcher, DocumentAcceptanceService acceptance, JdbcClient jdbc) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.acceptance = Objects.requireNonNull(acceptance, "acceptance");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Where a document came from, when it was imported from a URL. */
    public record Source(String url, Instant fetchedAt) {}

    /**
     * Fetches and stores the page. Fetch problems raise {@link PageImportException}; upload
     * validation problems raise the usual upload rejection.
     */
    public DocumentAcceptanceService.Receipt importPage(String address, UUID correlationId)
            throws IOException, InterruptedException {
        var page = fetcher.fetch(address);
        var receipt =
                acceptance.accept(
                        fileName(page),
                        page.utf8().length,
                        new ByteArrayInputStream(page.utf8()),
                        correlationId);
        jdbc.sql(
                        """
                INSERT INTO document_sources(document_id, source_url, fetched_at) VALUES (:id, :url, :at)
                ON CONFLICT (document_id) DO NOTHING
                """)
                .param("id", receipt.documentId())
                .param("url", page.finalUri().toString())
                .param("at", Timestamp.from(page.fetchedAt()))
                .update();
        return receipt;
    }

    public Optional<Source> source(UUID documentId) {
        return jdbc.sql(
                        """
                        SELECT s.source_url, s.fetched_at FROM document_sources s
                        JOIN documents d ON d.document_id = s.document_id
                        WHERE s.document_id = :id AND d.deleted_at IS NULL
                        """)
                .param("id", documentId)
                .query(
                        (row, index) ->
                                new Source(
                                        row.getString("source_url"),
                                        row.getTimestamp("fetched_at").toInstant()))
                .optional();
    }

    /** A readable, safe file name such as {@code example.com-guide.html}. */
    static String fileName(PageFetcher.Page page) {
        var uri = page.finalUri();
        var path = uri.getPath() == null ? "" : uri.getPath();
        var last = path.substring(path.lastIndexOf('/') + 1);
        var dot = last.lastIndexOf('.');
        if (dot > 0) last = last.substring(0, dot);
        var base = (uri.getHost() + (last.isBlank() ? "" : "-" + last)).toLowerCase(Locale.ROOT);
        base = base.replaceAll("[^a-z0-9._-]+", "-").replaceAll("-{2,}", "-");
        if (base.length() > 100) base = base.substring(0, 100);
        var extension =
                switch (page.mediaType()) {
                    case "text/plain" -> ".txt";
                    case "text/markdown" -> ".md";
                    default -> ".html";
                };
        return base + extension;
    }
}
