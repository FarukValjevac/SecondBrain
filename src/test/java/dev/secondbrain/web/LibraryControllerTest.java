package dev.secondbrain.web;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@WebMvcTest(LibraryController.class)
@ExtendWith(OutputCaptureExtension.class)
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

    @Test
    void correlatesTheResponseAndJsonLogWithoutRequestContent(CapturedOutput output)
            throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(
                        MockMvcRequestBuilders.get("/")
                                .header("X-Correlation-ID", id)
                                .header("Authorization", "Bearer sentinel-private-credential")
                                .queryParam("q", "sentinel-private-query"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.header().string("X-Correlation-ID", id));

        var lines =
                output.getOut()
                        .lines()
                        .filter(line -> line.contains(id) && line.contains("http_request"))
                        .toList();
        Assertions.assertThat(lines).hasSize(1);
        var entry = JsonMapper.builder().build().readTree(lines.getFirst());
        Assertions.assertThat(entry.get("correlation_id").asString()).isEqualTo(id);
        Assertions.assertThat(entry.get("operation").asString()).isEqualTo("http_request");
        Assertions.assertThat(entry.get("outcome").asString()).isEqualTo("completed");
        Assertions.assertThat(entry.get("status").asInt()).isEqualTo(200);
        Assertions.assertThat(entry.get("duration_ms").asLong()).isNotNegative();
        Assertions.assertThat(output.getOut()).doesNotContain("sentinel-private");
        Assertions.assertThat(MDC.get("correlation_id")).isNull();
    }
}
