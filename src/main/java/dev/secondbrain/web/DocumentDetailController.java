package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentDeletionService;
import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.DocumentProcessingService;
import dev.secondbrain.documents.OriginalDocumentService;
import dev.secondbrain.folders.FolderService;
import dev.secondbrain.imports.WebPageImportService;
import dev.secondbrain.notes.Highlight;
import dev.secondbrain.notes.HighlightService;
import dev.secondbrain.questions.CitationMarkers;
import dev.secondbrain.questions.QuestionAnswerService;
import dev.secondbrain.summaries.SummaryService;

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

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    private final ObjectProvider<SummaryService> summaries;
    private final ObjectProvider<WebPageImportService> imports;
    private final ObjectProvider<FolderService> folders;
    private final ObjectProvider<OriginalDocumentService> originals;
    private final ObjectProvider<HighlightService> highlights;

    DocumentDetailController(
            DocumentInspectionService documents,
            DocumentDeletionService deletion,
            ObjectProvider<DocumentProcessingService> processing,
            ObjectProvider<QuestionAnswerService> answers,
            ObjectProvider<SummaryService> summaries,
            ObjectProvider<WebPageImportService> imports,
            ObjectProvider<FolderService> folders,
            ObjectProvider<OriginalDocumentService> originals,
            ObjectProvider<HighlightService> highlights) {
        this.documents = documents;
        this.deletion = deletion;
        this.processing = processing;
        this.answers = answers;
        this.summaries = summaries;
        this.imports = imports;
        this.folders = folders;
        this.originals = originals;
        this.highlights = highlights;
    }

    @GetMapping("/documents/{id}")
    String detail(
            @PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestParam(name = "view", required = false)
                    String view,
            @org.springframework.web.bind.annotation.RequestParam(name = "from", required = false)
                    String from,
            @org.springframework.web.bind.annotation.RequestParam(name = "to", required = false)
                    String to,
            Model model) {
        var document =
                documents
                        .find(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("document", document);
        // PDFs open on the original; everything else on the reading view. Unknown views fall back.
        var original = Optional.<OriginalDocumentService.Original>empty();
        var originalService = originals.getIfAvailable();
        if (originalService != null) {
            try {
                original = originalService.find(id);
            } catch (DataAccessException | TransactionException unavailable) {
                // The original view is optional; the text views still work.
            }
        }
        original.ifPresent(found -> model.addAttribute("original", found));
        boolean pdf =
                original.map(found -> found.kind() == OriginalDocumentService.Kind.PDF)
                        .orElse(false);
        var selected =
                "original".equals(view) && pdf
                        ? "original"
                        : "lines".equals(view)
                                ? "lines"
                                : "reading".equals(view) || !pdf ? "reading" : "original";
        model.addAttribute("view", selected);
        var notes = highlights.getIfAvailable();
        if (notes != null) {
            try {
                var found = notes.forDocument(id);
                model.addAttribute("highlights", found);
                model.addAttribute("colors", java.util.List.of(Highlight.Color.values()));
                // Each highlighted line gets the colour of its latest highlight.
                var lines = new java.util.HashMap<Integer, String>();
                for (var highlight : found)
                    for (int line = highlight.startLine(); line <= highlight.endLine(); line++)
                        lines.put(line, highlight.color().token());
                model.addAttribute("highlightedLines", lines);
            } catch (DataAccessException | TransactionException unavailable) {
                // Highlights are optional; the document still reads.
            }
        }
        // A cited passage (from a source link) is highlighted; anything malformed is ignored.
        citedRange(from, to)
                .ifPresent(
                        range -> {
                            model.addAttribute("citedFrom", range[0]);
                            model.addAttribute("citedTo", range[1]);
                        });
        try {
            documents.ocrProgress(id).ifPresent(found -> model.addAttribute("ocrProgress", found));
        } catch (DataAccessException | TransactionException unavailable) {
            // Progress is optional.
        }
        if (selected.equals("reading"))
            model.addAttribute("readingBlocks", ReadingView.blocks(document.lines()));
        var service = processing.getIfAvailable();
        if (service != null) model.addAttribute("progress", service.read(Set.of(id)).get(id));
        model.addAttribute("questionsEnabled", answers.getIfAvailable() != null);
        var folderService = folders.getIfAvailable();
        if (folderService != null) {
            try {
                folderService
                        .folderOf(id)
                        .ifPresent(folder -> model.addAttribute("documentFolder", folder));
            } catch (DataAccessException | TransactionException unavailable) {
                // The folder line is optional.
            }
        }
        var pages = imports.getIfAvailable();
        if (pages != null) {
            try {
                pages.source(id).ifPresent(source -> model.addAttribute("webSource", source));
            } catch (DataAccessException | TransactionException unavailable) {
                // The source line is optional.
            }
        }
        var summarizer = summaries.getIfAvailable();
        model.addAttribute("summariesEnabled", summarizer != null);
        if (summarizer != null) {
            try {
                summarizer
                        .find(id)
                        .ifPresent(
                                summary -> {
                                    model.addAttribute("summary", summary);
                                    model.addAttribute(
                                            "summarySegments",
                                            CitationMarkers.segments(
                                                    summary.text(), summary.sources().size()));
                                });
            } catch (DataAccessException | TransactionException unavailable) {
                // The summary is optional; the document page still shows its text.
            }
        }
        return "document";
    }

    @PostMapping("/documents/{id}/summary")
    RedirectView summarize(
            @PathVariable UUID id,
            @RequestAttribute(RequestCorrelationFilter.ATTRIBUTE) UUID correlationId,
            RedirectAttributes feedback) {
        var summarizer = summaries.getIfAvailable();
        if (summarizer == null || documents.find(id).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        summarizer.request(id, correlationId);
        feedback.addFlashAttribute(
                "summaryMessage", "A new summary is being prepared. Refresh in a moment.");
        var redirect = new RedirectView("/documents/" + id);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
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
        var redirect = new RedirectView("/library", true);
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

    /** Lines {@code from..to} of a cited passage: positive, ordered, at most 500 lines. */
    static Optional<int[]> citedRange(String from, String to) {
        if (from == null || to == null || !from.matches("[0-9]{1,6}") || !to.matches("[0-9]{1,6}"))
            return Optional.empty();
        int start = Integer.parseInt(from);
        int end = Integer.parseInt(to);
        if (start < 1 || end < start || end - start > 500) return Optional.empty();
        return Optional.of(new int[] {start, end});
    }
}
