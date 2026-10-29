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

/** Default startup has no SearchService or live credentials. The page must still work. */
@WebMvcTest(controllers = SearchController.class)
class SearchDisabledControllerTest {
    @Autowired private MockMvc mvc;

    @Test
    void rendersUnavailableSearchWithoutRequiringAProvider() throws Exception {
        String html =
                mvc.perform(get("/search"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html).contains("Search unavailable").doesNotContain("name=\"q\"");
        String submitted =
                mvc.perform(
                                post("/search")
                                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                        .param("q", "notes")
                                        .param("scope", "all"))
                        .andExpect(status().isServiceUnavailable())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(submitted).contains("Search unavailable").doesNotContain("Matching passages:");
    }
}
