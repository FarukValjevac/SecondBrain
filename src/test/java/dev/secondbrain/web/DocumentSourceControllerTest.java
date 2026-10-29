package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.DocumentInspectionService.PassageSource;
import dev.secondbrain.documents.DocumentInspectionService.SourceLocation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Source access needs no embedding provider or live inference, including after disabling
 * embeddings.
 */
@WebMvcTest(
        controllers = DocumentSourceController.class,
        properties = {"secondbrain.embeddings.enabled=false", "spring.config.import="})
@ExtendWith(OutputCaptureExtension.class)
class DocumentSourceControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private DocumentInspectionService documents;
    private final UUID document = UUID.randomUUID();
    private final UUID passage = UUID.randomUUID();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rendersOnlyTheResolvedEscapedPassageWithSourceCoordinatesAndNoCache(
            boolean pdf, CapturedOutput output) throws Exception {
        when(documents.source(document, passage)).thenReturn(Optional.of(source(pdf)));
        var html =
                mvc.perform(get("/documents/{documentId}/passages/{passageId}", document, passage))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("Referrer-Policy", "no-referrer"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(
                        "sentinel-private-&lt;script&gt;notes&lt;/script&gt;",
                        "Café 📚 &lt;img src=x onerror=bad&gt;\nnext line",
                        "Lines 3–4",
                        "href=\"/documents/" + document + "\"",
                        "Back to search")
                .doesNotContain("<script>", "<img src=x", "http-equiv=\"refresh\"");
        if (pdf) assertThat(html).contains("Page 7");
        else assertThat(html).doesNotContain("Page 7");
        verify(documents).source(document, passage);
        verify(documents, never()).find(any());
        assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @Test
    void rereadsTheSameLinkAndShowsNoOldTextWhenItBecomesUnavailable(CapturedOutput output)
            throws Exception {
        when(documents.source(document, passage))
                .thenReturn(Optional.of(source(true)))
                .thenReturn(Optional.empty());
        mvc.perform(get("/documents/{documentId}/passages/{passageId}", document, passage))
                .andExpect(status().isOk());
        var html =
                mvc.perform(get("/documents/{documentId}/passages/{passageId}", document, passage))
                        .andExpect(status().isNotFound())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("Referrer-Policy", "no-referrer"))
                        .andExpect(model().attributeDoesNotExist("source"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains("This passage is no longer available", "Search again")
                .doesNotContain(
                        "sentinel-private", "Café", document.toString(), passage.toString());
        verify(documents, org.mockito.Mockito.times(2)).source(document, passage);
        assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "sentinel-private-invalid",
                "1-1-1-1-1",
                "00000000-0000-0000-0000-00000000000g"
            })
    void rejectsMalformedOrNoncanonicalIdsWithoutLookingUpContent(String id, CapturedOutput output)
            throws Exception {
        for (var pair : new String[][] {{id, passage.toString()}, {document.toString(), id}}) {
            var html =
                    mvc.perform(
                                    get(
                                            "/documents/{documentId}/passages/{passageId}",
                                            pair[0],
                                            pair[1]))
                            .andExpect(status().isBadRequest())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(model().attributeDoesNotExist("source"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(html)
                    .contains("Use a valid document and passage link")
                    .doesNotContain("sentinel-private");
        }
        verifyNoInteractions(documents);
        assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @Test
    void dataFailuresAreSafeUnavailableResponsesRatherThanOldContent(CapturedOutput output)
            throws Exception {
        when(documents.source(document, passage))
                .thenThrow(new DataAccessResourceFailureException("sentinel-private-SQL"));
        var html =
                mvc.perform(get("/documents/{documentId}/passages/{passageId}", document, passage))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("Referrer-Policy", "no-referrer"))
                        .andExpect(model().attributeDoesNotExist("source"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains("The source could not be opened")
                .doesNotContain("sentinel-private", document.toString());
        assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @Test
    void invalidStoredSourceIsNotMisreportedAsBadUserInput() throws Exception {
        when(documents.source(document, passage))
                .thenThrow(new IllegalArgumentException("Invalid stored source"));
        mvc.perform(get("/documents/{documentId}/passages/{passageId}", document, passage))
                .andExpect(status().isServiceUnavailable())
                .andExpect(model().attributeDoesNotExist("source"));
    }

    private PassageSource source(boolean pdf) {
        return new PassageSource(
                document,
                passage,
                7,
                "sentinel-private-<script>notes</script>",
                "Café 📚 <img src=x onerror=bad>\nnext line",
                new SourceLocation(3, 2, 4, 9, pdf ? OptionalInt.of(7) : OptionalInt.empty()));
    }
}
