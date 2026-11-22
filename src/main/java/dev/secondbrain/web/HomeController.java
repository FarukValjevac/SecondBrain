package dev.secondbrain.web;

import dev.secondbrain.conversations.ConversationService;
import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.questions.QuestionAnswerService;

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

import java.util.UUID;

/**
 * The conversation home: a new chat at {@code /}, a saved conversation at {@code /chats/{id}}, the
 * list of all chats, and renaming and deleting them. Answers come from the question flow.
 */
@Controller
class HomeController {
    private final ObjectProvider<QuestionAnswerService> answers;
    private final ObjectProvider<ConversationService> conversations;
    private final ObjectProvider<DocumentInspectionService> documents;

    HomeController(
            ObjectProvider<QuestionAnswerService> answers,
            ObjectProvider<ConversationService> conversations,
            ObjectProvider<DocumentInspectionService> documents) {
        this.answers = answers;
        this.conversations = conversations;
        this.documents = documents;
    }

    @GetMapping("/")
    String home(
            @RequestParam(name = "folder", required = false) String folder,
            Model model,
            HttpServletResponse response) {
        // A folder page links here to ask about that folder; it only preselects the menu.
        model.addAttribute("homeFolder", FolderForm.choice(folder).orElse(null));
        return page(model, response);
    }

    @GetMapping("/chats/{id}")
    String chat(@PathVariable UUID id, Model model, HttpServletResponse response) {
        var conversation =
                service()
                        .find(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("conversation", conversation);
        model.addAttribute("currentChat", conversation.conversationId());
        model.addAttribute("savedTurns", SavedTurns.of(conversation, documents.getIfAvailable()));
        var scope = conversation.lastScope();
        if (scope.startsWith("folder:"))
            model.addAttribute("homeFolder", UUID.fromString(scope.substring("folder:".length())));
        return page(model, response);
    }

    @GetMapping("/chats")
    String all(Model model, HttpServletResponse response) {
        noStore(response);
        model.addAttribute("chats", service().recent(500));
        return "chats";
    }

    @PostMapping("/chats/{id}/rename")
    RedirectView rename(
            @PathVariable UUID id, @RequestParam(name = "title", required = false) String title) {
        if (!service().rename(id, title)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return redirect("/chats/" + id);
    }

    @GetMapping("/chats/{id}/delete")
    String confirmDelete(@PathVariable UUID id, Model model, HttpServletResponse response) {
        noStore(response);
        model.addAttribute(
                "conversation",
                service()
                        .find(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "chat-delete";
    }

    @PostMapping("/chats/{id}/delete")
    RedirectView delete(@PathVariable UUID id, RedirectAttributes flash) {
        service().delete(id);
        flash.addFlashAttribute("chatMessage", "Chat deleted.");
        return redirect("/chats");
    }

    @GetMapping("/chats/delete-all")
    String confirmDeleteAll(HttpServletResponse response) {
        noStore(response);
        return "chat-delete";
    }

    @PostMapping("/chats/delete-all")
    RedirectView deleteAll(RedirectAttributes flash) {
        int deleted = service().deleteAll();
        flash.addFlashAttribute(
                "chatMessage", deleted == 1 ? "1 chat deleted." : deleted + " chats deleted.");
        return redirect("/chats");
    }

    private String page(Model model, HttpServletResponse response) {
        noStore(response);
        model.addAttribute("questionsEnabled", answers.getIfAvailable() != null);
        model.addAttribute("chatsEnabled", conversations.getIfAvailable() != null);
        return "home";
    }

    private ConversationService service() {
        var service = conversations.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return service;
    }

    /** Chats hold private questions and answers: never cached and never sent as a referrer. */
    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    private static RedirectView redirect(String path) {
        var redirect = new RedirectView(path, true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
    }
}
