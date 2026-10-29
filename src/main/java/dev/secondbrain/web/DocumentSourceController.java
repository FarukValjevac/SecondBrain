package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentInspectionService;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

/** Source text is resolved by documents; URLs never carry a query or a cached excerpt. */
@Controller
@ConditionalOnProperty(
        name = "secondbrain.events.enabled",
        havingValue = "true",
        matchIfMissing = true)
class DocumentSourceController {
    private final DocumentInspectionService documents;

    DocumentSourceController(DocumentInspectionService documents) {
        this.documents = documents;
    }

    @ModelAttribute
    void preventCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @GetMapping("/documents/{documentId}/passages/{passageId}")
    String source(
            @PathVariable String documentId,
            @PathVariable String passageId,
            Model model,
            HttpServletResponse response) {
        final UUID document;
        final UUID passage;
        try {
            document = id(documentId);
            passage = id(passageId);
        } catch (IllegalArgumentException invalid) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("sourceError", "Use a valid document and passage link.");
            return "document-source";
        }
        var source = documents.source(document, passage);
        if (source.isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            model.addAttribute(
                    "sourceError",
                    "This passage is no longer available. Search again for current sources.");
        } else {
            model.addAttribute("source", source.orElseThrow());
        }
        return "document-source";
    }

    @ExceptionHandler({
        DataAccessException.class,
        TransactionException.class,
        IllegalStateException.class,
        IllegalArgumentException.class
    })
    String unavailable(Model model, HttpServletResponse response) {
        preventCaching(response);
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        model.asMap().remove("source");
        model.addAttribute("sourceError", "The source could not be opened. Try again later.");
        return "document-source";
    }

    private static UUID id(String value) {
        if (value.length() != 36) throw new IllegalArgumentException("Invalid source identity");
        var id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value))
            throw new IllegalArgumentException("Invalid source identity");
        return id;
    }
}
