package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.questions.QuestionAnswerService;
import dev.secondbrain.questions.QuestionFailure;
import dev.secondbrain.search.SearchQuery;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * HTTP binding/presentation only; the public question API owns generation and source acceptance.
 */
@Controller
class QuestionController {
    private final ObjectProvider<QuestionAnswerService> answers;
    private final ObjectProvider<DocumentInspectionService> documents;

    QuestionController(
            ObjectProvider<QuestionAnswerService> answers,
            ObjectProvider<DocumentInspectionService> documents) {
        this.answers = answers;
        this.documents = documents;
    }

    @ModelAttribute
    void prepare(Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        DocumentQueryView.restore(DocumentQueryForm.blank(0), model);
        model.addAttribute("retryAt", Optional.empty());
        model.addAttribute(
                "questionsEnabled",
                answers.getIfAvailable() != null && documents.getIfAvailable() != null);
        if (!Boolean.TRUE.equals(model.getAttribute("questionsEnabled")))
            model.addAttribute("questionFeedback", QuestionFeedback.DISABLED);
    }

    @GetMapping("/questions")
    String form(
            @RequestParam MultiValueMap<String, String> fields,
            Model model,
            HttpServletResponse response) {
        final int page;
        try {
            var pages = fields.getOrDefault("page", List.of("0"));
            if (pages.size() != 1) throw new IllegalArgumentException("Invalid document page");
            page = DocumentQueryForm.page(pages.getFirst());
        } catch (IllegalArgumentException invalid) {
            return invalid(model, response);
        }
        if (Boolean.TRUE.equals(model.getAttribute("questionsEnabled"))) {
            var form = DocumentQueryForm.blank(page);
            DocumentQueryView.restore(form, model);
            try {
                DocumentQueryView.populate(form, documents.getObject(), model);
            } catch (DataAccessException | TransactionException unavailable) {
                return feedback(model, response, QuestionFeedback.DATABASE, Optional.empty());
            }
        }
        return "questions";
    }

    @PostMapping(value = "/questions", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    String submit(
            @RequestParam MultiValueMap<String, String> fields,
            Model model,
            HttpServletResponse response) {
        final DocumentQueryForm form;
        try {
            form = DocumentQueryForm.read(fields, "ask");
        } catch (IllegalArgumentException invalid) {
            return invalid(model, response);
        }
        DocumentQueryView.restore(form, model);
        final Optional<SearchQuery> question;
        try {
            question =
                    form.action() == DocumentQueryForm.Action.SUBMIT
                            ? Optional.of(form.toQuery())
                            : Optional.empty();
        } catch (IllegalArgumentException invalidQuestion) {
            return feedback(model, response, QuestionFeedback.INVALID_QUESTION, Optional.empty());
        }
        var service = answers.getIfAvailable();
        if (service == null || documents.getIfAvailable() == null)
            return feedback(model, response, QuestionFeedback.DISABLED, Optional.empty());
        try {
            // Load picker metadata before generation so no later DB work follows the final source
            // check.
            DocumentQueryView.populate(form, documents.getObject(), model);
            if (form.action() == DocumentQueryForm.Action.BROWSE) return "questions";
            var request = question.orElseThrow();
            model.addAttribute("queryText", request.text());
            var result = service.answer(request);
            if (result.outcome() == QuestionAnswerService.Outcome.INSUFFICIENT_INFORMATION)
                return feedback(
                        model,
                        response,
                        form.scope() == DocumentQueryForm.Scope.SELECTED
                                        && form.documentIds().isEmpty()
                                ? QuestionFeedback.EMPTY_SELECTION
                                : QuestionFeedback.INSUFFICIENT_INFORMATION,
                        Optional.empty());
            model.addAttribute("questionAnswer", result);
            model.addAttribute("questionFeedback", QuestionFeedback.ANSWERED);
        } catch (QuestionFailure failure) {
            if (failure.reason() == QuestionFailure.Reason.SOURCE_UNAVAILABLE
                    || failure.reason() == QuestionFailure.Reason.INVALID_EVIDENCE)
                DocumentQueryView.restore(form, model);
            return feedback(
                    model, response, QuestionFeedback.from(failure.reason()), failure.retryAt());
        } catch (DataAccessException | TransactionException unavailable) {
            return feedback(model, response, QuestionFeedback.DATABASE, Optional.empty());
        }
        return "questions";
    }

    private static String feedback(
            Model model,
            HttpServletResponse response,
            QuestionFeedback feedback,
            Optional<Instant> retryAt) {
        response.setStatus(feedback.getStatus().value());
        model.asMap().remove("questionAnswer");
        model.addAttribute("questionFeedback", feedback);
        model.addAttribute("retryAt", feedback.acceptsRetryHint() ? retryAt : Optional.empty());
        return "questions";
    }

    private static String invalid(Model model, HttpServletResponse response) {
        model.addAttribute("scope", "selected");
        return feedback(model, response, QuestionFeedback.INVALID_FORM, Optional.empty());
    }
}
