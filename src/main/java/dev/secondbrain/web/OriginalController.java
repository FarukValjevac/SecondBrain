package dev.secondbrain.web;

import dev.secondbrain.documents.OriginalDocumentService;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Streams a visible document's stored original. PDFs open inline in the browser's own viewer; every
 * other type, and any download request, is sent as an attachment. Nothing is ever served as HTML or
 * script, so an imported page cannot run in the application's origin.
 */
@Controller
class OriginalController {
    private final ObjectProvider<OriginalDocumentService> originals;

    OriginalController(ObjectProvider<OriginalDocumentService> originals) {
        this.originals = originals;
    }

    @GetMapping("/documents/{id}/original")
    void original(
            @PathVariable UUID id,
            @RequestParam(name = "download", required = false) String download,
            HttpServletResponse response)
            throws IOException {
        var service = originals.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var opened =
                service.open(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try (opened) {
            var original = opened.original();
            boolean inline =
                    download == null && original.kind() == OriginalDocumentService.Kind.PDF;
            response.setContentType(inline ? "application/pdf" : "application/octet-stream");
            response.setHeader(
                    HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.builder(inline ? "inline" : "attachment")
                            .filename(original.name(), StandardCharsets.UTF_8)
                            .build()
                            .toString());
            response.setContentLengthLong(original.size());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Referrer-Policy", "no-referrer");
            // The viewer may be framed by SecondBrain's own document page only.
            response.setHeader("Content-Security-Policy", "frame-ancestors 'self'");
            opened.stream().transferTo(response.getOutputStream());
        }
    }
}
