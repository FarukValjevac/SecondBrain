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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
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
        // PDFs open on the original; everything else on the reading view. Unknown views fall back.
        var original = Optional.<OriginalDocumentService.Original>empty();
        var originalService = originals.getIfAvailable();
        if (originalService != null)
            original = optional(() -> originalService.find(id)).flatMap(found -> found);
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
        // The original PDF view shows the stored file, so the extracted text is not loaded.
        var document =
                (selected.equals("original") ? documents.findWithoutText(id) : documents.find(id))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("document", document);

        // Each highlighted line gets the colour of its latest highlight.
        var highlighted = new java.util.HashMap<Integer, String>();
        var notes = highlights.getIfAvailable();
        if (notes != null)
            optional(() -> notes.forDocument(id))
                    .ifPresent(
                            found -> {
                                model.addAttribute("highlights", found);
                                model.addAttribute(
                                        "colors", java.util.List.of(Highlight.Color.values()));
                                for (var highlight : found)
                                    for (int line = highlight.startLine();
                                            line <= highlight.endLine();
                                            line++)
                                        highlighted.put(line, highlight.color().token());
                            });
        // A cited passage (from a source link) is highlighted; anything malformed is ignored.
        var cited = citedRange(from, to);
        cited.ifPresent(
                range -> {
                    model.addAttribute("citedFrom", range[0]);
                    model.addAttribute("citedTo", range[1]);
                });
        model.addAttribute(
                "marks",
                new LineMarks(
                        cited.map(range -> range[0]).orElse(0),
                        cited.map(range -> range[1]).orElse(-1),
                        highlighted));
        if (selected.equals("reading"))
            model.addAttribute("readingBlocks", ReadingView.blocks(document.lines()));

        // Optional sections: each is left out when its feature is off or cannot be read.
        optional(() -> documents.ocrProgress(id))
                .flatMap(found -> found)
                .ifPresent(found -> model.addAttribute("ocrProgress", found));
        var service = processing.getIfAvailable();
        if (service != null) model.addAttribute("progress", service.read(Set.of(id)).get(id));
        model.addAttribute("questionsEnabled", answers.getIfAvailable() != null);
        var folderService = folders.getIfAvailable();
        if (folderService != null)
            optional(() -> folderService.folderOf(id))
                    .flatMap(found -> found)
                    .ifPresent(folder -> model.addAttribute("documentFolder", folder));
        var pages = imports.getIfAvailable();
        if (pages != null)
            optional(() -> pages.source(id))
                    .flatMap(found -> found)
                    .ifPresent(source -> model.addAttribute("webSource", source));
        var summarizer = summaries.getIfAvailable();
        model.addAttribute("summariesEnabled", summarizer != null);
        if (summarizer != null)
            optional(() -> summarizer.find(id))
                    .flatMap(found -> found)
                    .ifPresent(
                            summary -> {
                                model.addAttribute("summary", summary);
                                model.addAttribute(
                                        "summarySegments",
                                        CitationMarkers.segments(
                                                summary.text(), summary.sources().size()));
                            });
        return "document";
    }

    /** Reads an optional page section; a database failure leaves the section out. */
    private static <T> Optional<T> optional(java.util.function.Supplier<T> read) {
        try {
            return Optional.ofNullable(read.get());
        } catch (DataAccessException | TransactionException unavailable) {
            return Optional.empty();
        }
    }

    @PostMapping("/documents/{id}/summary")
    RedirectView summarize(
            @PathVariable UUID id,
            @RequestAttribute(RequestCorrelationFilter.ATTRIBUTE) UUID correlationId,
            RedirectAttributes feedback) {
        var summarizer = summaries.getIfAvailable();
        if (summarizer == null || documents.findWithoutText(id).isEmpty())
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
    ModelAndView statusFailure(ResponseStatusException failure) {
        String message =
                failure.getStatusCode().value() == 409
                        ? "This step cannot be retried. Refresh its status; it may already be queued, paused, changed or removed."
                        : failure.getStatusCode().value() == 400
                                ? "Refresh the document page before retrying."
                                : "Document not found.";
        return ErrorPage.of(failure.getStatusCode(), message);
    }

    @ExceptionHandler({
        DataAccessException.class,
        TransactionException.class,
        IllegalStateException.class
    })
    ModelAndView unavailable() {
        return ErrorPage.of(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Documents are temporarily unavailable. Try again later.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ModelAndView invalidId() {
        return ErrorPage.of(HttpStatus.BAD_REQUEST, "Use a valid document ID.");
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
