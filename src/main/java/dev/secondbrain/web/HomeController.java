package dev.secondbrain.web;

import dev.secondbrain.questions.QuestionAnswerService;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The conversation home. Answers come from the question flow; this page only renders the box. */
@Controller
class HomeController {
    private final ObjectProvider<QuestionAnswerService> answers;

    HomeController(ObjectProvider<QuestionAnswerService> answers) {
        this.answers = answers;
    }

    @GetMapping("/")
    String home(
            @RequestParam(name = "folder", required = false) String folder,
            Model model,
            HttpServletResponse response) {
        // A folder page links here to ask about that folder; it only preselects the menu.
        model.addAttribute("homeFolder", FolderForm.choice(folder).orElse(null));
        // The thread exists only in the page; nothing about it is cached or sent onward.
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        model.addAttribute("questionsEnabled", answers.getIfAvailable() != null);
        return "home";
    }
}
