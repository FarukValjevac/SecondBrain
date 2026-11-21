package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentDeletionService;
import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.DocumentProcessingService;
import dev.secondbrain.questions.QuestionAnswerService;
import dev.secondbrain.search.SearchFailure;
import dev.secondbrain.search.SearchResult;
import dev.secondbrain.search.SearchService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@ConditionalOnProperty(
        name = "secondbrain.events.enabled",
        havingValue = "true",
        matchIfMissing = true)
class DocumentDetailController {
    private final DocumentInspectionService documents;
    private final DocumentDeletionService deletion;
    private final ObjectProvider<DocumentProcessingService> processing;
    private final ObjectProvider<QuestionAnswerService> answers;
    private final ObjectProvider<SearchService> search;

    DocumentDetailController(
            DocumentInspectionService documents,
            DocumentDeletionService deletion,
            ObjectProvider<DocumentProcessingService> processing,
            ObjectProvider<QuestionAnswerService> answers,
            ObjectProvider<SearchService> search) {
        this.documents = documents;
        this.deletion = deletion;
        this.processing = processing;
        this.answers = answers;
        this.search = search;
    }

    @GetMapping("/documents/{id}")
    String detail(@PathVariable UUID id, Model model) {
        model.addAttribute(
                "document",
                documents
                        .find(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        var service = processing.getIfAvailable();
        if (service != null) model.addAttribute("progress", service.read(Set.of(id)).get(id));
        model.addAttribute("questionsEnabled", answers.getIfAvailable() != null);
        model.addAttribute("related", related(id));
        return "document";
    }

    /** Related documents are optional: without search or on a read failure the page omits them. */
    private List<RelatedDocument> related(UUID id) {
        var service = search.getIfAvailable();
        if (service == null) return List.of();
        try {
            var matches = service.related(id).matches();
            var names =
                    documents.names(
                            matches.stream()
                                    .map(SearchResult.Match::documentId)
                                    .collect(Collectors.toSet()));
            // A document deleted since the similarity read has no name and is dropped.
            return matches.stream()
                    .filter(match -> names.containsKey(match.documentId()))
                    .map(
                            match ->
                                    new RelatedDocument(
                                            match.documentId(),
                                            names.get(match.documentId()),
                                            match))
                    .toList();
        } catch (SearchFailure | DataAccessException | TransactionException unavailable) {
            return List.of();
        }
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
            @RequestParam MultiValueMap<String, String> fields,
            @RequestAttribute(RequestCorrelationFilter.ATTRIBUTE) UUID correlationId,
            RedirectAttributes feedback) {
        final UUID delivery;
        final int cycle;
        try {
            var ids = fields.get("deliveryId");
            var cycles = fields.get("cycle");
            if (ids == null || ids.size() != 1 || cycles == null || cycles.size() != 1)
                throw new IllegalArgumentException("Missing retry selection");
            delivery = UUID.fromString(ids.getFirst());
            if (!delivery.toString().equalsIgnoreCase(ids.getFirst())
                    || !cycles.getFirst().matches("[0-9]{1,10}"))
                throw new IllegalArgumentException("Invalid retry selection");
            cycle = Integer.parseInt(cycles.getFirst());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Refresh the document page before retrying.");
        }
        var service = processing.getIfAvailable();
        if (service == null || !service.retry(id, delivery, cycle, correlationId))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This step cannot be retried. Refresh its status.");
        feedback.addFlashAttribute(
                "retryMessage", "Processing retry queued. Saved progress has been retained.");
        var redirect = new RedirectView("/documents/" + id);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
    }

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    ResponseEntity<String> statusFailure(ResponseStatusException failure) {
        String message =
                failure.getStatusCode().value() == 409
                        ? "This step cannot be retried. Refresh its status; it may already be queued, paused, changed or removed."
                        : failure.getStatusCode().value() == 400
                                ? "Refresh the document page before retrying."
                                : "Document not found.";
        return ResponseEntity.status(failure.getStatusCode()).body(message);
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
