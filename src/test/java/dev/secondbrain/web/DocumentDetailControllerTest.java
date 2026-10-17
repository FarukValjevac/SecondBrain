package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.ExtractionState;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@WebMvcTest(DocumentDetailController.class)
class DocumentDetailControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private DocumentInspectionService documents;
    private final UUID id = UUID.randomUUID();

    @Test
    void rendersEscapedNamesAndLiteralUnicodeMarkupWithLineAnchors() throws Exception {
        when(documents.find(id))
                .thenReturn(
                        Optional.of(
                                new DocumentInspectionService.DocumentView(
                                        id,
                                        "<script>name</script>",
                                        1,
                                        ExtractionState.EXTRACTED,
                                        "",
                                        false,
                                        List.of(
                                                new DocumentInspectionService.Line(
                                                        1, "Café <script>alert(1)</script>"),
                                                new DocumentInspectionService.Line(2, "")))));
        var html =
                mvc.perform(get("/documents/" + id))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html)
                .contains(
                        "Café &lt;script&gt;",
                        "&lt;script&gt;name",
                        "id=\"line-1\"",
                        "id=\"line-2\"")
                .doesNotContain("<script>", "http-equiv=\"refresh\"", "Retry extraction");
    }

    @Test
    void refreshesPendingPagesAndOffersRetryOnlyOnFailure() throws Exception {
        for (var state :
                List.of(
                        ExtractionState.QUEUED,
                        ExtractionState.PROCESSING,
                        ExtractionState.FAILED)) {
            when(documents.find(id))
                    .thenReturn(
                            Optional.of(
                                    new DocumentInspectionService.DocumentView(
                                            id,
                                            "notes.txt",
                                            1,
                                            state,
                                            state == ExtractionState.FAILED
                                                    ? "Use valid UTF-8."
                                                    : "",
                                            state == ExtractionState.FAILED,
                                            List.of())));
            var html =
                    mvc.perform(get("/documents/" + id))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            if (state == ExtractionState.FAILED)
                assertThat(html)
                        .contains("Retry extraction", "Use valid UTF-8.")
                        .doesNotContain("http-equiv=\"refresh\"");
            else
                assertThat(html)
                        .contains("http-equiv=\"refresh\"")
                        .doesNotContain("Retry extraction");
        }
    }

    @Test
    void returnsNotFoundAndRejectsMalformedIds() throws Exception {
        when(documents.find(id)).thenReturn(Optional.empty());
        mvc.perform(get("/documents/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/documents/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void retriesWithRequestCorrelationAndRedirectsWithoutResubmitting() throws Exception {
        var correlation = UUID.randomUUID();
        when(documents.retry(id, correlation)).thenReturn(true);
        mvc.perform(post("/documents/" + id + "/retry").header("X-Correlation-ID", correlation))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/documents/" + id));
        verify(documents).retry(id, correlation);
    }

    @Test
    void rejectsAnIneligibleRetry() throws Exception {
        when(documents.retry(eq(id), any())).thenReturn(false);
        mvc.perform(post("/documents/" + id + "/retry")).andExpect(status().isConflict());
    }

    @Test
    void hidesDatabaseFailureDetails() throws Exception {
        when(documents.find(id))
                .thenThrow(new DataAccessResourceFailureException("private-path-secret"));
        var body =
                mvc.perform(get("/documents/" + id))
                        .andExpect(status().isServiceUnavailable())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).contains("temporarily unavailable").doesNotContain("private-path-secret");
    }
}
