package dev.secondbrain.web;

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
    private final ChatTurns chats;

    QuestionController(
            ObjectProvider<QuestionAnswerService> answers,
            ObjectProvider<DocumentInspectionService> documents,
            ObjectProvider<FolderService> folders,
            ObjectProvider<ConversationService> conversations) {
        this.answers = answers;
        this.documents = documents;
        this.folders = folders;
        this.chats = new ChatTurns(conversations);
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
        return answer(fields, model, response, Optional.empty()).view();
    }

    /**
     * The same question flow, rendered as one conversation turn for the home page thread, and saved
     * in its conversation. Only the view differs: validation, generation and citation checks are
     * shared.
     */
    @PostMapping(value = "/questions/turn", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    String turn(
            @RequestParam MultiValueMap<String, String> fields,
            Model model,
            HttpServletResponse response) {
        // Unique within the page, so several answers never repeat element ids.
        model.addAttribute("turnId", "turn-" + UUID.randomUUID().toString().substring(0, 8));
        var view = conversationTurn(fields, model, response).view();
        return view.equals("questions") ? "fragments/answer :: turn" : view;
    }

    /** Without JavaScript the home page posts here: answer, save, then show the conversation. */
    @PostMapping(value = "/chats/ask", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    Object ask(
            @RequestParam MultiValueMap<String, String> fields,
            Model model,
            HttpServletResponse response) {
        var turn = conversationTurn(fields, model, response);
        if (turn.view().equals("questions") && turn.saved().isPresent()) {
            var redirect =
                    new org.springframework.web.servlet.view.RedirectView(
                            "/chats/" + turn.saved().get(), true);
            redirect.setStatusCode(org.springframework.http.HttpStatus.SEE_OTHER);
            return redirect;
        }
        return turn.view();
    }

    /** What a conversation request produced: the view, and the chat it was saved in, if any. */
    private record Turn(String view, Optional<UUID> saved) {}

    /** Answers with the conversation's previous question as context and saves the turn. */
    private Turn conversationTurn(
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
            return new Turn(invalid(model, response), Optional.empty());
        }
        var asked = answer(fields, model, response, chats.previousQuestion(conversation));
        if (!asked.view().equals("questions")) return new Turn(asked.view(), Optional.empty());
        var saved = chats.save(conversation, asked);
        saved.ifPresent(id -> model.addAttribute("conversationId", id));
        if (saved.isEmpty() && asked.saveable()) model.addAttribute("conversationUnsaved", true);
        return new Turn(asked.view(), saved);
    }

    /**
     * One answered (or refused) question: the rendered view plus what a chat needs to save it. The
     * question is the user's own text; any conversation context only helped retrieval.
     */
    record Asked(
            String view,
            Optional<String> question,
            Optional<String> scope,
            QuestionFeedback feedback,
            Optional<QuestionAnswerService.Answer> answer) {
        static Asked shown(String view, QuestionFeedback feedback) {
            return new Asked(view, Optional.empty(), Optional.empty(), feedback, Optional.empty());
        }

        /** Answered and insufficient-information turns are saved; errors are not part of a chat. */
        boolean saveable() {
            return question.isPresent()
                    && scope.isPresent()
                    && (feedback == QuestionFeedback.ANSWERED
                            || feedback == QuestionFeedback.INSUFFICIENT_INFORMATION);
        }
    }

    private Asked answer(
            MultiValueMap<String, String> fields,
            Model model,
            HttpServletResponse response,
            Optional<String> context) {
        final DocumentQueryForm form;
        try {
            form = DocumentQueryForm.read(fields, "ask");
        } catch (IllegalArgumentException invalid) {
            return Asked.shown(invalid(model, response), QuestionFeedback.INVALID_FORM);
        }
        DocumentQueryView.restore(form, model);
        final Optional<SearchQuery> question;
        try {
            question =
                    form.action() == DocumentQueryForm.Action.SUBMIT
                            ? Optional.of(form.toQuery())
                            : Optional.empty();
        } catch (IllegalArgumentException invalidQuestion) {
            return Asked.shown(
                    feedback(model, response, QuestionFeedback.INVALID_QUESTION, Optional.empty()),
                    QuestionFeedback.INVALID_QUESTION);
        }
        var service = answers.getIfAvailable();
        if (service == null || documents.getIfAvailable() == null)
            return Asked.shown(
                    feedback(model, response, QuestionFeedback.DISABLED, Optional.empty()),
                    QuestionFeedback.DISABLED);
        // Only whole-library and folder questions belong to a saved chat.
        var scope =
                switch (form.scope()) {
                    case ALL -> Optional.of("all");
                    case FOLDER -> form.folder().map(folder -> "folder:" + folder);
                    case SELECTED -> Optional.<String>empty();
                };
        try {
            // Load picker metadata before generation so no later DB work follows the final source
            // check.
            DocumentQueryView.populate(form, documents.getObject(), model);
            if (form.action() == DocumentQueryForm.Action.BROWSE)
                return Asked.shown("questions", null);
            SearchQuery request;
            try {
                request = FolderScope.resolve(form, question.orElseThrow(), folders);
            } catch (FolderScope.Problem problem) {
                var feedback = QuestionFeedback.from(problem.reason());
                return Asked.shown(feedback(model, response, feedback, Optional.empty()), feedback);
            }
            // The user's own question is shown and saved; context only helps retrieval.
            var asked = request.text();
            if (context.isPresent()) request = ChatTurns.withContext(context.get(), request);
            model.addAttribute("queryText", asked);
            var result = service.answer(request);
            if (result.outcome() == QuestionAnswerService.Outcome.INSUFFICIENT_INFORMATION) {
                var feedback =
                        form.scope() == DocumentQueryForm.Scope.SELECTED
                                        && form.documentIds().isEmpty()
                                ? QuestionFeedback.EMPTY_SELECTION
                                : QuestionFeedback.INSUFFICIENT_INFORMATION;
                return new Asked(
                        feedback(model, response, feedback, Optional.empty()),
                        Optional.of(asked),
                        scope,
                        feedback,
                        Optional.empty());
            }
            model.addAttribute("questionAnswer", result);
            model.addAttribute(
                    "answerSegments",
                    CitationMarkers.segments(result.text(), result.citations().size()));
            model.addAttribute("questionFeedback", QuestionFeedback.ANSWERED);
            return new Asked(
                    "questions",
                    Optional.of(asked),
                    scope,
                    QuestionFeedback.ANSWERED,
                    Optional.of(result));
        } catch (QuestionFailure failure) {
            if (failure.reason() == QuestionFailure.Reason.SOURCE_UNAVAILABLE
                    || failure.reason() == QuestionFailure.Reason.INVALID_EVIDENCE)
                DocumentQueryView.restore(form, model);
            var feedback = QuestionFeedback.from(failure.reason());
            return Asked.shown(feedback(model, response, feedback, failure.retryAt()), feedback);
        } catch (DataAccessException | TransactionException unavailable) {
            return Asked.shown(
                    feedback(model, response, QuestionFeedback.DATABASE, Optional.empty()),
                    QuestionFeedback.DATABASE);
        }
    }

    private static String feedback(
            Model model,
            HttpServletResponse response,
            QuestionFeedback feedback,
            Optional<Instant> retryAt) {
        return PageFeedback.show(
                model,
                response,
                "questions",
                "questionFeedback",
                feedback,
                retryAt,
                "questionAnswer");
    }

    private static String invalid(Model model, HttpServletResponse response) {
        model.addAttribute("scope", "selected");
        return feedback(model, response, QuestionFeedback.INVALID_FORM, Optional.empty());
    }
}
