package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.secondbrain.documents.DocumentDeletionService;
import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.DocumentProcessingService;
import dev.secondbrain.documents.ExtractionState;
import dev.secondbrain.questions.QuestionAnswerService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** With questions enabled, ready documents link to a question limited to themselves. */
@WebMvcTest({DocumentDetailController.class, LibraryController.class})
class AskAboutDocumentTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private DocumentInspectionService documents;

    /** Sections come from the same stubbed document: one part holding all its lines. */
    @org.junit.jupiter.api.BeforeEach
    void sectionsFollowTheStubbedDocument() {
        org.mockito.Mockito.when(
                        documents.findSection(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        call ->
                                documents
                                        .find(call.getArgument(0))
                                        .map(
                                                view ->
                                                        new DocumentInspectionService.SectionView(
                                                                view,
                                                                new DocumentInspectionService
                                                                        .Section(
                                                                        1,
                                                                        1,
                                                                        java.util.List.of(
                                                                                "Lines")))));
    }

    @MockitoBean private DocumentDeletionService deletion;
    @MockitoBean private DocumentProcessingService processing;
    @MockitoBean private QuestionAnswerService answers;
    private final UUID id = UUID.randomUUID();

    @Test
    void aReadyDocumentLinksToAQuestionAboutOnlyThatDocument() throws Exception {
        document(DocumentProcessingService.State.READY);
        assertThat(page("/documents/" + id))
                .contains("href=\"/questions?documentId=" + id + "\"", "Ask about this document")
                .doesNotContain("once it is ready for search", "Questions are turned off");
        // Rendering the link is presentation only; no question is asked.
        verifyNoInteractions(answers);
    }

    @ParameterizedTest
    @EnumSource(
            value = DocumentProcessingService.State.class,
            names = {"READY"},
            mode = EnumSource.Mode.EXCLUDE)
    void aDocumentThatIsNotReadyExplainsWhyThereIsNoLink(DocumentProcessingService.State state)
            throws Exception {
        document(state);
        assertThat(page("/documents/" + id))
                .contains("You can ask about this document once it is ready for search.")
                .doesNotContain("/questions?documentId=");
    }

    @Test
    void libraryRowsOfferAnAccessibleAskLinkOnlyForReadyDocuments() throws Exception {
        var waiting = UUID.randomUUID();
        when(documents.listFolder(0, java.util.Optional.empty()))
                .thenReturn(
                        new DocumentInspectionService.LibraryPage(
                                List.of(
                                        new DocumentInspectionService.DocumentSummary(
                                                id,
                                                "ready-notes.md",
                                                ExtractionState.EXTRACTED,
                                                Instant.EPOCH),
                                        new DocumentInspectionService.DocumentSummary(
                                                waiting,
                                                "waiting-notes.md",
                                                ExtractionState.EXTRACTED,
                                                Instant.EPOCH)),
                                0,
                                false));
        when(processing.read(Set.of(id, waiting)))
                .thenReturn(
                        Map.of(
                                id, progress(DocumentProcessingService.State.READY),
                                waiting, progress(DocumentProcessingService.State.PROCESSING)));
        when(processing.cleanup()).thenReturn(new DocumentProcessingService.Cleanup(0, 0));
        assertThat(page("/library"))
                .contains(
                        "href=\"/questions?documentId=" + id + "\"",
                        "aria-label=\"Ask about ready-notes.md\"")
                .doesNotContain("/questions?documentId=" + waiting);
    }

    private void document(DocumentProcessingService.State state) {
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
        when(processing.read(Set.of(id))).thenReturn(Map.of(id, progress(state)));
    }

    private static DocumentProcessingService.Progress progress(
            DocumentProcessingService.State state) {
        return new DocumentProcessingService.Progress(
                DocumentProcessingService.Stage.EMBEDDINGS,
                state,
                "Status message.",
                1,
                1,
                1,
                Optional.empty(),
                Optional.empty());
    }

    private String page(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
