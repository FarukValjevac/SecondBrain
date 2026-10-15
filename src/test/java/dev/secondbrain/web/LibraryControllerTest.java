package dev.secondbrain.web;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.nio.charset.StandardCharsets;

@WebMvcTest(LibraryController.class)
class LibraryControllerTest {

    private final MockMvc mvc;

    @Autowired
    LibraryControllerTest(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void rendersTheEmptyLibraryWithoutUnimplementedControls() throws Exception {
        var response =
                mvc.perform(MockMvcRequestBuilders.get("/"))
                        .andExpect(MockMvcResultMatchers.status().isOk())
                        .andExpect(
                                MockMvcResultMatchers.content()
                                        .contentTypeCompatibleWith(MediaType.TEXT_HTML))
                        .andExpect(MockMvcResultMatchers.view().name("library"))
                        .andReturn()
                        .getResponse();

        Assertions.assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains(
                        "Document library",
                        "No documents yet",
                        "Your library is empty.",
                        "0 documents")
                .contains("href=\"/css/library.css\"")
                .doesNotContain("<form", "<button", "type=\"file\"", "th:href");
    }
}
