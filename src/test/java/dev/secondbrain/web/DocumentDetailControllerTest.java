package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.secondbrain.documents.DocumentDeletionService;
import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.DocumentProcessingService;
import dev.secondbrain.documents.ExtractionState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

@WebMvcTest({DocumentDetailController.class, LibraryController.class})
@ExtendWith(OutputCaptureExtension.class)
class DocumentDetailControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private DocumentInspectionService documents;
    @MockitoBean private DocumentDeletionService deletion;
    @MockitoBean private DocumentProcessingService processing;
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
                .contains("href=\"/documents/" + id + "/delete\"", "Delete document")
                .doesNotContain("<script>", "http-equiv=\"refresh\"", "Retry extraction", ">Page ");
    }

    @Test
    void rendersPhysicalPdfPagesAsEscapedStableSourceLinksIncludingBlankPages() throws Exception {
        when(documents.find(id))
                .thenReturn(
                        Optional.of(
                                new DocumentInspectionService.DocumentView(
                                        id,
                                        "<script>notes</script>.pdf",
                                        1,
                                        ExtractionState.EXTRACTED,
                                        "",
                                        List.of(
                                                new DocumentInspectionService.Line(
                                                        1,
                                                        "Café <script>alert(1)</script>",
                                                        OptionalInt.of(1)),
                                                new DocumentInspectionService.Line(
                                                        2, "", OptionalInt.of(2)),
                                                new DocumentInspectionService.Line(
                                                        3, "Last page", OptionalInt.of(3))))));

        var html =
                mvc.perform(get("/documents/" + id))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(html)
                .contains(
                        "&lt;script&gt;notes&lt;/script&gt;.pdf",
                        "Café &lt;script&gt;",
                        "id=\"line-1\"",
                        "id=\"line-2\"",
                        "id=\"line-3\"",
                        "href=\"#line-1\"",
                        "href=\"#line-2\"",
                        "href=\"#line-3\"",
                        "aria-label=\"Page 1, line 1\"",
                        "aria-label=\"Page 2, line 2\"",
                        "aria-label=\"Page 3, line 3\"",
                        ">Page 1</a>",
                        ">Page 2</a>",
                        ">Page 3</a>",
                        "Delete document")
                .doesNotContain("<script>", "http-equiv=\"refresh\"", "Retry extraction");
    }

    @Test
    void confirmationEscapesTheNameOffersCancelAndDoesNotDelete(CapturedOutput output)
            throws Exception {
        when(documents.summary(id))
                .thenReturn(
                        Optional.of(
                                new DocumentInspectionService.DocumentSummary(
                                        id,
                                        "sentinel-private-<script>name</script>.md",
                                        ExtractionState.QUEUED,
                                        Instant.EPOCH)));
        var html =
                mvc.perform(get("/documents/" + id + "/delete"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(
                        "Delete document?",
                        "sentinel-private-&lt;script&gt;name&lt;/script&gt;.md",
                        "method=\"post\"",
                        "action=\"/documents/" + id + "/delete\"",
                        "href=\"/documents/" + id + "\"",
                        "Cancel",
                        "Your source file is unchanged",
                        "cannot be undone")
                .doesNotContain("<script>", "http-equiv=\"refresh\"");
        verifyNoInteractions(deletion);
        assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deletionRedirectsToOneTimeLibraryFeedbackWithoutResubmitting(boolean requested)
            throws Exception {
        var correlation = UUID.randomUUID();
        when(deletion.request(id, correlation)).thenReturn(requested);
        when(documents.list(0))
                .thenReturn(new DocumentInspectionService.LibraryPage(List.of(), 0, false));
        var result =
                mvc.perform(
                                post("/documents/" + id + "/delete")
                                        .header("X-Correlation-ID", correlation))
                        .andExpect(status().isSeeOther())
                        .andExpect(redirectedUrl("/"))
                        .andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        var html =
                mvc.perform(get("/").session(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(
                        "Library updated",
                        "role=\"status\"",
                        requested
                                ? "Document removed from the library"
                                : "already absent from the library");
        var refreshed =
                mvc.perform(get("/").session(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(refreshed).doesNotContain("Library updated");
        verify(deletion).request(id, correlation);
    }

    @Test
    void malformedOrMissingConfirmationTargetsNeverInvokeDeletion() throws Exception {
        when(documents.summary(id)).thenReturn(Optional.empty());
        mvc.perform(get("/documents/" + id + "/delete")).andExpect(status().isNotFound());
        mvc.perform(get("/documents/not-an-id/delete")).andExpect(status().isBadRequest());
        mvc.perform(post("/documents/not-an-id/delete")).andExpect(status().isBadRequest());
        verifyNoInteractions(deletion);
    }

    @Test
    void deletionFailureHasNoSuccessFeedbackOrPrivateErrorDetails(CapturedOutput output)
            throws Exception {
        when(deletion.request(eq(id), any()))
                .thenThrow(new DataAccessResourceFailureException("sentinel-private-db"));
        var body =
                mvc.perform(post("/documents/" + id + "/delete"))
                        .andExpect(status().isServiceUnavailable())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).isEqualTo("Documents are temporarily unavailable. Try again later.");
        assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(DocumentProcessingService.State.class)
    void rendersPipelineStatesAndOffersOnlyEligibleRetry(DocumentProcessingService.State state)
            throws Exception {
        when(documents.find(id))
                .thenReturn(
                        Optional.of(
                                new DocumentInspectionService.DocumentView(
                                        id,
                                        "notes.txt",
                                        1,
                                        ExtractionState.EXTRACTED,
                                        "",
                                        List.of())));
        var delivery = UUID.randomUUID();
        var option =
                state == DocumentProcessingService.State.FAILED
                        ? Optional.of(new DocumentProcessingService.Retry(delivery, 2))
                        : Optional.<DocumentProcessingService.Retry>empty();
        when(processing.read(Set.of(id)))
                .thenReturn(
                        Map.of(
                                id,
                                new DocumentProcessingService.Progress(
                                        DocumentProcessingService.Stage.EMBEDDINGS,
                                        state,
                                        "Check settings before retrying.",
                                        10,
                                        5,
                                        2,
                                        option,
                                        Optional.empty())));
        var html =
                mvc.perform(get("/documents/" + id))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(state.getLabel(), "Preparing search", "Refresh status", "10", "5", "2")
                .doesNotContain("http-equiv=\"refresh\"", "%");
        if (state == DocumentProcessingService.State.FAILED)
            assertThat(html)
                    .contains("Retry processing", "value=\"" + delivery + "\"", "value=\"2\"");
        else assertThat(html).doesNotContain("Retry processing");
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
        var delivery = UUID.randomUUID();
        when(processing.retry(id, delivery, 2, correlation)).thenReturn(true);
        mvc.perform(
                        post("/documents/" + id + "/retry")
                                .param("deliveryId", delivery.toString())
                                .param("cycle", "2")
                                .header("X-Correlation-ID", correlation))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/documents/" + id));
        verify(processing).retry(id, delivery, 2, correlation);
    }

    @Test
    void rejectsAnIneligibleRetry() throws Exception {
        mvc.perform(
                        post("/documents/" + id + "/retry")
                                .param("deliveryId", UUID.randomUUID().toString())
                                .param("cycle", "0"))
                .andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-1", "2147483648", "1.1", "private-invalid"})
    void rejectsMissingOrInvalidRetryCyclesBeforeMutation(String cycle) throws Exception {
        mvc.perform(
                        post("/documents/" + id + "/retry")
                                .param("deliveryId", UUID.randomUUID().toString())
                                .param("cycle", cycle))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(processing);
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
