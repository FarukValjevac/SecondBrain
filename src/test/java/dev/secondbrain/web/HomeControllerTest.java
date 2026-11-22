package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.secondbrain.questions.QuestionAnswerService;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HomeController.class)
class HomeControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private QuestionAnswerService answers;

    @Test
    void opensOnAConversationThatStillWorksWithoutJavaScript() throws Exception {
        var html =
                mvc.perform(get("/"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("Referrer-Policy", "no-referrer"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(
                        "What do you want to know?",
                        // The plain form posts to the normal answer page.
                        "action=\"/questions\"",
                        "data-chat",
                        // chat.js posts here; form.action is shadowed by the button named action.
                        "data-turn=\"/questions/turn\"",
                        "name=\"q\"",
                        "name=\"scope\" value=\"all\"",
                        "<label for=\"ask\" class=\"visually-hidden\">",
                        "class=\"thread\"",
                        "aria-live=\"polite\"",
                        "src=\"/js/chat.js\"",
                        "href=\"/\" aria-current=\"page\"",
                        "href=\"/library\"")
                .doesNotContain("Questions are turned off");
        Mockito.verifyNoInteractions(answers);
    }
}
