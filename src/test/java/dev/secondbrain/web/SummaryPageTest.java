package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import dev.secondbrain.summaries.DocumentSummary;
import dev.secondbrain.summaries.SummaryService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

@WebMvcTest(DocumentDetailController.class)
class SummaryPageTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private DocumentInspectionService documents;
    @MockitoBean private DocumentDeletionService deletion;
    @MockitoBean private DocumentProcessingService processing;
    @MockitoBean private SummaryService summaries;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void document() {
        when(documents.find(id))
                .thenReturn(
                        Optional.of(
                                new DocumentInspectionService.DocumentView(
                                        id,
                                        "notes.md",
                                        1,
                                        ExtractionState.EXTRACTED,
                                        "",
                                        List.of(new DocumentInspectionService.Line(1, "Text")))));
        when(processing.read(Set.of(id)))
                .thenReturn(
                        Map.of(
                                id,
                                new DocumentProcessingService.Progress(
                                        DocumentProcessingService.Stage.EMBEDDINGS,
                                        DocumentProcessingService.State.READY,
                                        "Ready.",
                                        1,
                                        1,
                                        1,
                                        Optional.empty(),
                                        Optional.empty())));
    }

    @Test
    void showsTheSummaryWithLinkedNumberedSourcesAndARegenerateButton() throws Exception {
        var passage = UUID.randomUUID();
        when(summaries.find(id))
                .thenReturn(
                        Optional.of(
                                new DocumentSummary(
                                        "Lists keep <b>order</b> [1].",
                                        Instant.EPOCH,
                                        List.of(
                                                new DocumentInspectionService.PassageSource(
                                                        id,
                                                        passage,
                                                        1,
                                                        "notes.md",
                                                        "ArrayList keeps order.",
                                                        new DocumentInspectionService
                                                                .SourceLocation(
                                                                2,
                                                                0,
                                                                3,
                                                                10,
                                                                OptionalInt.empty()))))));
        assertThat(page())
                .contains(
                        "Summary",
                        "Lists keep &lt;b&gt;order&lt;/b&gt; ",
                        "href=\"#summary-source-1\"",
                        "id=\"summary-source-1\"",
                        "href=\"/documents/" + id + "/passages/" + passage + "\"",
                        "Lines 2–3",
                        "Regenerate summary")
                .doesNotContain("<b>order");
    }

    @Test
    void offersAFirstSummaryWhenNoneIsStored() throws Exception {
        when(summaries.find(id)).thenReturn(Optional.empty());
        assertThat(page()).contains("No summary yet", "Summarize now");
    }

    @Test
    void regenerationIsQueuedNotPerformedInTheRequest() throws Exception {
        mvc.perform(post("/documents/" + id + "/summary"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/documents/" + id));
        verify(summaries).request(any(), any());
    }

    @Test
    void unknownDocumentsCannotBeSummarized() throws Exception {
        var unknown = UUID.randomUUID();
        when(documents.find(unknown)).thenReturn(Optional.empty());
        mvc.perform(post("/documents/" + unknown + "/summary")).andExpect(status().isNotFound());
        verifyNoInteractions(summaries);
    }

    private String page() throws Exception {
        return mvc.perform(get("/documents/" + id))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
