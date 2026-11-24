package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.notes.Highlight;
import dev.secondbrain.notes.HighlightService;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;
import java.util.UUID;

/** Highlights and notes: adding while reading, editing, deleting, and the Notes page. */
@Controller
class HighlightController {
    private final ObjectProvider<HighlightService> highlights;
    private final ObjectProvider<DocumentInspectionService> documents;

    HighlightController(
            ObjectProvider<HighlightService> highlights,
            ObjectProvider<DocumentInspectionService> documents) {
        this.highlights = highlights;
        this.documents = documents;
    }

    @PostMapping("/documents/{id}/highlights")
    RedirectView add(
            @PathVariable UUID id,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "note", required = false) String note,
            @RequestParam(name = "color", required = false) String color,
            RedirectAttributes flash) {
        var service = service();
        var library = documents.getIfAvailable();
        if (library == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var range =
                DocumentDetailController.citedRange(from, to)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));
        // Only the selected lines are read; the quote comes from them, never from the browser.
        var document =
                library.lines(id, range[0], range[1])
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var quote = new StringBuilder();
        for (var line : document.lines())
            if (line.number() >= range[0] && line.number() <= range[1]) {
                if (!quote.isEmpty() && !line.text().isBlank()) quote.append(' ');
                quote.append(line.text().strip());
                if (quote.length() > Highlight.MAXIMUM_QUOTE) break;
            }
        if (quote.toString().isBlank()) {
            flash.addFlashAttribute("highlightError", "Select some text to highlight.");
            return reading(id, range[0]);
        }
        try {
            var saved =
                    service.add(
                            id,
                            document.processingVersion(),
                            range[0],
                            range[1],
                            quote.toString(),
                            note,
                            Highlight.Color.parse(color == null ? "yellow" : color));
            flash.addFlashAttribute(
                    saved.isPresent() ? "highlightMessage" : "highlightError",
                    saved.isPresent()
                            ? "Highlight saved."
                            : "The document changed. Reload it and highlight again.");
        } catch (IllegalArgumentException invalid) {
            flash.addFlashAttribute("highlightError", "Use a note of up to 4,000 characters.");
        }
        return reading(id, range[0]);
    }

    @PostMapping("/highlights/{id}")
    RedirectView update(
            @PathVariable UUID id,
            @RequestParam(name = "note", required = false) String note,
            @RequestParam(name = "color", required = false) String color,
            @RequestParam(name = "return", required = false) String back,
            RedirectAttributes flash) {
        final java.util.Optional<UUID> document;
        try {
            document =
                    service()
                            .update(
                                    id,
                                    note,
                                    Highlight.Color.parse(color == null ? "yellow" : color));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (document.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        flash.addFlashAttribute("highlightMessage", "Note saved.");
        return back(back, document.get());
    }

    @PostMapping("/highlights/{id}/delete")
    RedirectView delete(
            @PathVariable UUID id,
            @RequestParam(name = "return", required = false) String back,
            RedirectAttributes flash) {
        var document = service().delete(id);
        if (document.isEmpty()) return redirect("/notes");
        flash.addFlashAttribute("highlightMessage", "Highlight removed.");
        return back(back, document.get());
    }

    @GetMapping("/notes")
    String notes(
            @RequestParam(name = "q", required = false) String filter,
            Model model,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        var text = filter == null ? "" : filter.strip();
        model.addAttribute("filter", text.length() > 200 ? text.substring(0, 200) : text);
        model.addAttribute("notes", service().recent(text, 300));
        model.addAttribute("colors", List.of(Highlight.Color.values()));
        return "notes";
    }

    private HighlightService service() {
        var service = highlights.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return service;
    }

    /** Only two fixed destinations: never a caller-supplied address. */
    private static RedirectView back(String back, UUID document) {
        return "notes".equals(back)
                ? redirect("/notes")
                : redirect("/documents/" + document + "?view=reading#notes");
    }

    private static RedirectView reading(UUID document, int line) {
        return redirect("/documents/" + document + "?view=reading#line-" + line);
    }

    private static RedirectView redirect(String path) {
        var redirect = new RedirectView(path, true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
    }
}
