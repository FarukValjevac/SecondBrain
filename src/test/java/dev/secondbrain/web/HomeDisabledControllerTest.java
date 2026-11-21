package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HomeController.class)
class HomeDisabledControllerTest {
    @Autowired private MockMvc mvc;

    @Test
    void withoutQuestionsOffersTheLibraryInsteadOfABrokenBox() throws Exception {
        var html =
                mvc.perform(get("/"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains("Questions are turned off", "Open your library")
                .doesNotContain("data-chat", "name=\"q\"");
    }
}
