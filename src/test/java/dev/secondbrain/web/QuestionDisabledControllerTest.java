package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = QuestionController.class, properties = "spring.config.import=")
class QuestionDisabledControllerTest {
    @Autowired private MockMvc mvc;

    @Test
    void defaultGetAndPostNeedNoCredentialsOrQuestionService() throws Exception {
        var form =
                mvc.perform(get("/questions"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(form)
                .contains("Questions unavailable")
                .doesNotContain("name=\"q\"", "id=\"answer-title\"");
        var submitted =
                mvc.perform(
                                post("/questions")
                                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                        .param("q", "Synthetic question")
                                        .param("scope", "all"))
                        .andExpect(status().isServiceUnavailable())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(submitted)
                .contains("Questions unavailable")
                .doesNotContain("id=\"answer-title\"");
    }
}
