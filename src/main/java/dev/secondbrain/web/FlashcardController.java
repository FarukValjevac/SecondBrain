package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.questions.ChatFailure;
import dev.secondbrain.study.FlashcardService;
import dev.secondbrain.study.ReviewScheduler;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Flashcard generation and review pages; HTTP binding only. */
@Controller
class FlashcardController {
    private final ObjectProvider<FlashcardService> flashcards;
    private final ObjectProvider<DocumentInspectionService> documents;

    FlashcardController(
            ObjectProvider<FlashcardService> flashcards,
            ObjectProvider<DocumentInspectionService> documents) {
        this.flashcards = flashcards;
        this.documents = documents;
    }

    @PostMapping("/documents/{id}/flashcards")
    RedirectView generate(@PathVariable UUID id, RedirectAttributes flash) {
        var service = available();
        if (library().find(id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String message;
        try {
            int created = service.generate(id);
            message =
                    created == 0
                            ? "No flashcards could be made from this document. Try again later."
                            : "Created " + created + " flashcards. They are due now.";
        } catch (ChatFailure failure) {
            message =
                    switch (failure.reason()) {
                        case RATE_LIMITED, QUOTA_EXHAUSTED ->
                                "The free allowance is used up for now. Try again later.";
                        default -> "Flashcards could not be made right now. Try again later.";
                    };
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        flash.addFlashAttribute("studyMessage", message);
        return redirect("/flashcards");
    }

    @GetMapping("/flashcards")
    String review(Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        var service = available();
        model.addAttribute("dueCount", service.dueCount());
        service.nextDue()
                .ifPresent(
                        card -> {
                            model.addAttribute("card", card);
                            model.addAttribute(
                                    "documentName",
                                    library()
                                            .names(Set.of(card.documentId()))
                                            .getOrDefault(card.documentId(), "Document"));
                            library()
                                    .source(card.documentId(), card.passageId())
                                    .ifPresent(source -> model.addAttribute("source", source));
                        });
        return "flashcards";
    }

    @PostMapping("/flashcards/{id}/review")
    RedirectView grade(
            @PathVariable UUID id, @RequestParam String grade, RedirectAttributes flash) {
        var service = available();
        final ReviewScheduler.Grade parsed;
        try {
            parsed = ReviewScheduler.Grade.valueOf(grade.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (!service.review(id, parsed))
            flash.addFlashAttribute(
                    "studyMessage",
                    "That card was already reviewed or removed. Here is the next one.");
        return redirect("/flashcards");
    }

    private FlashcardService available() {
        var service = flashcards.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return service;
    }

    private DocumentInspectionService library() {
        var library = documents.getIfAvailable();
        if (library == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return library;
    }

    private static RedirectView redirect(String path) {
        var redirect = new RedirectView(path, true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ResponseBody
    String unavailable() {
        return "Flashcards are unavailable right now. Try again later.";
    }
}
