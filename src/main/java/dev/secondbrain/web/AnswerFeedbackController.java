package dev.secondbrain.web;

import dev.secondbrain.questions.AnswerFeedback;
import dev.secondbrain.questions.AnswerFeedbackService;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Records explicit answer ratings and exports them; HTTP binding only. */
@Controller
class AnswerFeedbackController {
    private final ObjectProvider<AnswerFeedbackService> feedback;
    private final AnswerSignature signature;

    AnswerFeedbackController(
            ObjectProvider<AnswerFeedbackService> feedback, AnswerSignature signature) {
        this.feedback = feedback;
        this.signature = signature;
    }

    @PostMapping(
            value = "/questions/feedback",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    RedirectView record(
            @RequestParam MultiValueMap<String, String> fields, RedirectAttributes flash) {
        var service = feedback.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        final String question;
        final String answer;
        final boolean helpful;
        final Optional<AnswerFeedback.Reason> reason;
        final List<String> sources;
        try {
            question = single(fields, "q");
            answer = single(fields, "answer");
            helpful =
                    switch (single(fields, "rating")) {
                        case "helpful" -> true;
                        case "not-helpful" -> false;
                        default -> throw new IllegalArgumentException("Invalid rating");
                    };
            var reasons = fields.getOrDefault("reason", List.of(""));
            if (reasons.size() != 1) throw new IllegalArgumentException("Invalid reason");
            reason =
                    reasons.getFirst().isEmpty()
                            ? Optional.empty()
                            : Optional.of(AnswerFeedback.Reason.valueOf(reasons.getFirst()));
            sources = fields.getOrDefault("source", List.of());
            if (!signature.matches(single(fields, "signature"), question, answer, sources))
                throw new IllegalArgumentException("Unsigned answer");
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Feedback could not be recorded. Ask the question again.");
        }
        var cited = new ArrayList<AnswerFeedback.Source>(sources.size());
        for (var source : sources) {
            var ids = source.split(":", -1);
            cited.add(new AnswerFeedback.Source(UUID.fromString(ids[0]), UUID.fromString(ids[1])));
        }
        if (!service.record(helpful, reason, question, answer, cited))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A source of this answer was deleted, so its feedback was not kept.");
        flash.addFlashAttribute(
                "feedbackMessage", "Thanks, your feedback was saved on this computer.");
        var redirect = new RedirectView("/questions", true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
    }

    @GetMapping(value = "/questions/feedback/export", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    ResponseEntity<List<Exported>> export(HttpServletResponse response) {
        var service = feedback.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var items =
                service.export().stream()
                        .map(
                                item ->
                                        new Exported(
                                                item.feedbackId(),
                                                item.createdAt().toString(),
                                                item.helpful(),
                                                item.reason().map(Enum::name).orElse(null),
                                                item.question(),
                                                item.answer(),
                                                item.sources()))
                        .toList();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"answer-feedback.json\"")
                .body(items);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    @ResponseBody
    ResponseEntity<String> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Feedback could not be saved right now. Try again later.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    ResponseEntity<String> refused(ResponseStatusException failure) {
        var reason = failure.getReason();
        return ResponseEntity.status(failure.getStatusCode())
                .contentType(MediaType.TEXT_PLAIN)
                .body(reason == null ? "Not found." : reason);
    }

    private static String single(MultiValueMap<String, String> fields, String name) {
        var values = fields.get(name);
        if (values == null || values.size() != 1)
            throw new IllegalArgumentException("A single form value is required");
        return values.getFirst();
    }

    /** One exported rating; field names are stable for evaluation tooling. */
    record Exported(
            UUID feedbackId,
            String createdAt,
            boolean helpful,
            String reason,
            String question,
            String answer,
            List<AnswerFeedback.Source> citations) {}
}
