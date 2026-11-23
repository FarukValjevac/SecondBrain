package dev.secondbrain.web;

import dev.secondbrain.conversations.Conversation;
import dev.secondbrain.conversations.ConversationService;
import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.folders.FolderService;
import dev.secondbrain.questions.CitationMarkers;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * HTTP binding/presentation only; the public question API owns generation and source acceptance.
 */
@Controller
class QuestionController {
    private final ObjectProvider<QuestionAnswerService> answers;
    private final ObjectProvider<DocumentInspectionService> documents;
    private final ObjectProvider<FolderService> folders;
    private final ObjectProvider<ConversationService> conversations;

    QuestionController(
            ObjectProvider<QuestionAnswerService> answers,
            ObjectProvider<DocumentInspectionService> documents,
            ObjectProvider<FolderService> folders,
            ObjectProvider<ConversationService> conversations) {
        this.answers = answers;
        this.documents = documents;
        this.folders = folders;
        this.conversations = conversations;
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
        final DocumentQueryForm form;
        try {
            var pages = fields.getOrDefault("page", List.of("0"));
            if (pages.size() != 1) throw new IllegalArgumentException("Invalid document page");
            var page = DocumentQueryForm.page(pages.getFirst());
            // A document page links here with its ID to ask about that document only.
            var selected = fields.get("documentId");
            if (selected != null && (selected.size() != 1 || page != 0))
                throw new IllegalArgumentException("Invalid document selection");
            // A folder page links here with its ID to ask about that folder.
            var folder = fields.get("folder");
            if (folder != null && (folder.size() != 1 || page != 0 || selected != null))
                throw new IllegalArgumentException("Invalid folder selection");
            form =
                    folder != null
                            ? DocumentQueryForm.folder(
                                    DocumentQueryForm.documentId(folder.getFirst()))
                            : selected == null
                                    ? DocumentQueryForm.blank(page)
                                    : DocumentQueryForm.selected(
                                            DocumentQueryForm.documentId(selected.getFirst()));
        } catch (IllegalArgumentException invalid) {
            return invalid(model, response);
        }
        if (Boolean.TRUE.equals(model.getAttribute("questionsEnabled"))) {
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
        return answer(fields, model, response, Optional.empty());
    }

    /**
     * The same question flow, rendered as one conversation turn for the home page thread, and saved
     * in its conversation. Only the view differs: validation, generation, citation checks and
     * signing are shared.
     */
    @PostMapping(value = "/questions/turn", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    String turn(
            @RequestParam MultiValueMap<String, String> fields,
            Model model,
            HttpServletResponse response) {
        // Unique within the page, so several answers never repeat element ids.
        model.addAttribute("turnId", "turn-" + UUID.randomUUID().toString().substring(0, 8));
        var view = conversationTurn(fields, model, response);
        return view.equals("questions") ? "fragments/answer :: turn" : view;
    }

    /** Without JavaScript the home page posts here: answer, save, then show the conversation. */
    @PostMapping(value = "/chats/ask", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    Object ask(
            @RequestParam MultiValueMap<String, String> fields,
            Model model,
            HttpServletResponse response) {
        var view = conversationTurn(fields, model, response);
        if (view.equals("questions") && model.getAttribute("conversationId") instanceof UUID id) {
            var redirect =
                    new org.springframework.web.servlet.view.RedirectView("/chats/" + id, true);
            redirect.setStatusCode(org.springframework.http.HttpStatus.SEE_OTHER);
            return redirect;
        }
        return view;
    }

    /** Answers with the conversation's previous question as context and saves the turn. */
    private String conversationTurn(
            MultiValueMap<String, String> fields, Model model, HttpServletResponse response) {
        final Optional<UUID> conversation;
        try {
            var values = fields.getOrDefault("conversation", List.of());
            if (values.size() > 1) throw new IllegalArgumentException("Repeated conversation");
            conversation =
                    values.isEmpty() || values.getFirst().isEmpty()
                            ? Optional.empty()
                            : Optional.of(DocumentQueryForm.documentId(values.getFirst()));
        } catch (IllegalArgumentException invalid) {
            return invalid(model, response);
        }
        var service = conversations.getIfAvailable();
        Optional<String> context = Optional.empty();
        if (service != null && conversation.isPresent()) {
            try {
                context = service.lastQuestion(conversation.get());
            } catch (DataAccessException | TransactionException unavailable) {
                // Context is a help, not a requirement.
            }
        }
        var view = answer(fields, model, response, context);
        if (service != null && view.equals("questions")) save(service, conversation, model);
        return view;
    }

    /** Saves answered and insufficient-information turns; errors are not part of a chat. */
    private static void save(
            ConversationService service, Optional<UUID> conversation, Model model) {
        var feedback = model.getAttribute("questionFeedback");
        var outcome =
                feedback == QuestionFeedback.ANSWERED
                        ? Conversation.Outcome.ANSWERED
                        : feedback == QuestionFeedback.INSUFFICIENT_INFORMATION
                                ? Conversation.Outcome.INSUFFICIENT
                                : null;
        var scope =
                "all".equals(model.getAttribute("scope"))
                        ? "all"
                        : "folder".equals(model.getAttribute("scope"))
                                        && model.getAttribute("scopeFolder") instanceof UUID folder
                                ? "folder:" + folder
                                : null;
        if (outcome == null
                || scope == null
                || !(model.getAttribute("queryText") instanceof String question)) return;
        var answer =
                model.getAttribute("questionAnswer") instanceof QuestionAnswerService.Answer found
                        ? found
                        : null;
        var sources = new ArrayList<Conversation.SourceRef>();
        if (answer != null)
            for (var source : answer.citations())
                sources.add(new Conversation.SourceRef(source.documentId(), source.passageId()));
        try {
            model.addAttribute(
                    "conversationId",
                    service.save(
                            conversation,
                            question,
                            scope,
                            outcome,
                            answer == null ? "" : answer.text(),
                            sources));
        } catch (DataAccessException
                | TransactionException
                | IllegalArgumentException
                | IllegalStateException unsaved) {
            // The answer is still shown; it just is not added to the saved conversation.
            model.addAttribute("conversationUnsaved", true);
        }
    }

    /**
     * Adds the previous question so a follow-up like "and why?" finds the right passages. The
     * combined text stays within the question limit; the shown and saved question is unchanged.
     */
    static SearchQuery withContext(String previous, SearchQuery request) {
        var question = request.text();
        var prefix = "Earlier question in this conversation: ";
        var middle = "\n\nCurrent question: ";
        int budget =
                SearchQuery.MAXIMUM_CODE_POINTS
                        - question.codePointCount(0, question.length())
                        - prefix.length()
                        - middle.length();
        if (budget < 20) return request;
        var earlier = previous.strip();
        if (earlier.codePointCount(0, earlier.length()) > budget)
            earlier = earlier.substring(0, earlier.offsetByCodePoints(0, budget));
        return new SearchQuery(
                prefix + earlier + middle + question, request.scope(), request.limit());
    }

    private String answer(
            MultiValueMap<String, String> fields,
            Model model,
            HttpServletResponse response,
            Optional<String> context) {
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
            SearchQuery request;
            try {
                request = FolderScope.resolve(form, question.orElseThrow(), folders);
            } catch (FolderScope.Problem problem) {
                return feedback(
                        model, response, QuestionFeedback.from(problem.reason()), Optional.empty());
            }
            // The user's own question is shown, signed and saved; context only helps retrieval.
            var asked = request.text();
            if (context.isPresent()) request = withContext(context.get(), request);
            model.addAttribute("queryText", asked);
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
            model.addAttribute(
                    "answerSegments",
                    CitationMarkers.segments(result.text(), result.citations().size()));
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
