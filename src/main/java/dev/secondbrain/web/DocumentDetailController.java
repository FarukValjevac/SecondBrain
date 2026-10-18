package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentDeletionService;
import dev.secondbrain.documents.DocumentInspectionService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.util.UUID;

@Controller
@ConditionalOnProperty(
        name = "secondbrain.events.enabled",
        havingValue = "true",
        matchIfMissing = true)
class DocumentDetailController {
    private final DocumentInspectionService documents;
    private final DocumentDeletionService deletion;

    DocumentDetailController(
            DocumentInspectionService documents, DocumentDeletionService deletion) {
        this.documents = documents;
        this.deletion = deletion;
    }

    @GetMapping("/documents/{id}")
    String detail(@PathVariable UUID id, Model model) {
        model.addAttribute(
                "document",
                documents
                        .find(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "document";
    }

    @GetMapping("/documents/{id}/delete")
    String confirmDeletion(@PathVariable UUID id, Model model) {
        model.addAttribute(
                "document",
                documents
                        .summary(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "document-delete";
    }

    @PostMapping("/documents/{id}/delete")
    RedirectView delete(
            @PathVariable UUID id,
            @RequestAttribute(RequestCorrelationFilter.ATTRIBUTE) UUID correlationId,
            RedirectAttributes feedback) {
        boolean requested = deletion.request(id, correlationId);
        feedback.addFlashAttribute(
                "deletionMessage",
                requested
                        ? "Document removed from the library. Saved content will be cleaned up in the background."
                        : "That document is already absent from the library.");
        var redirect = new RedirectView("/", true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
    }

    @PostMapping("/documents/{id}/retry")
    RedirectView retry(
            @PathVariable UUID id,
            @RequestAttribute(RequestCorrelationFilter.ATTRIBUTE) UUID correlationId) {
        if (!documents.retry(id, correlationId))
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        var redirect = new RedirectView("/documents/" + id);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
    }

    @ExceptionHandler({
        DataAccessException.class,
        TransactionException.class,
        IllegalStateException.class
    })
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ResponseBody
    String unavailable() {
        return "Documents are temporarily unavailable. Try again later.";
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    String invalidId() {
        return "Use a valid document ID.";
    }
}
