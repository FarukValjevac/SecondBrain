package dev.secondbrain.web;

import dev.secondbrain.questions.ChatFailure;
import dev.secondbrain.study.QuizService;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/** Practice quiz pages; HTTP binding only. */
@Controller
class QuizController {
    private final ObjectProvider<QuizService> quizzes;

    QuizController(ObjectProvider<QuizService> quizzes) {
        this.quizzes = quizzes;
    }

    @PostMapping(value = "/quizzes", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    RedirectView start(
            @RequestParam MultiValueMap<String, String> fields, RedirectAttributes flash) {
        var service = available();
        var ids = new ArrayList<UUID>();
        try {
            var values = fields.getOrDefault("documentId", List.of());
            if (values.isEmpty() || values.size() > 5)
                throw new IllegalArgumentException("Choose one to five documents");
            for (var value : values) ids.add(DocumentQueryForm.documentId(value));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        try {
            var quiz = service.create(ids);
            if (quiz.isPresent()) return redirect("/quizzes/" + quiz.orElseThrow());
            flash.addFlashAttribute(
                    "quizMessage", "No quiz could be made from this document. Try again later.");
        } catch (ChatFailure failure) {
            flash.addFlashAttribute("quizMessage", unavailableMessage(failure));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return redirect("/documents/" + ids.getFirst());
    }

    @GetMapping("/quizzes/{id}")
    String quiz(@PathVariable UUID id, Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        model.addAttribute(
                "quiz",
                available()
                        .find(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "quiz";
    }

    @PostMapping(
            value = "/quizzes/{id}/answers",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    RedirectView answer(
            @PathVariable UUID id,
            @RequestParam MultiValueMap<String, String> fields,
            RedirectAttributes flash) {
        var service = available();
        var answers = new HashMap<Integer, String>();
        for (int position = 1; position <= 5; position++) {
            var values = fields.get("answer-" + position);
            if (values == null) continue;
            if (values.size() != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            answers.put(position, values.getFirst());
        }
        try {
            if (!service.grade(id, answers))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (ChatFailure failure) {
            flash.addFlashAttribute("quizMessage", unavailableMessage(failure));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return redirect("/quizzes/" + id);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ResponseBody
    String unavailable() {
        return "Quizzes are unavailable right now. Try again later.";
    }

    private static String unavailableMessage(ChatFailure failure) {
        return switch (failure.reason()) {
            case RATE_LIMITED, QUOTA_EXHAUSTED ->
                    "The free allowance is used up for now. Try again later.";
            default -> "The quiz could not be prepared right now. Try again later.";
        };
    }

    private QuizService available() {
        var service = quizzes.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return service;
    }

    private static RedirectView redirect(String path) {
        var redirect = new RedirectView(path, true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
    }
}
