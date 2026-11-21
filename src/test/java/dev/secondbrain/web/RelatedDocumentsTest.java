package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.secondbrain.documents.DocumentDeletionService;
import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.DocumentProcessingService;
import dev.secondbrain.documents.ExtractionState;
import dev.secondbrain.search.SearchFailure;
import dev.secondbrain.search.SearchResult;
import dev.secondbrain.search.SearchService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

@WebMvcTest(DocumentDetailController.class)
class RelatedDocumentsTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private DocumentInspectionService documents;
    @MockitoBean private DocumentDeletionService deletion;
    @MockitoBean private DocumentProcessingService processing;
    @MockitoBean private SearchService search;
    private final UUID id = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID gone = UUID.randomUUID();

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
    }

    @Test
    void listsSimilarDocumentsWithEscapedNamesAndPassageLinks() throws Exception {
        var passage = UUID.randomUUID();
        when(search.related(id))
                .thenReturn(new SearchResult(List.of(match(other, passage), match(gone, null))));
        // The second document was deleted after the similarity read, so it has no name.
        when(documents.names(any())).thenReturn(Map.of(other, "<b>linked</b>.md"));
        assertThat(page())
                .contains(
                        "Related documents",
                        "&lt;b&gt;linked&lt;/b&gt;.md",
                        "href=\"/documents/" + other + "\"",
                        "href=\"/documents/" + other + "/passages/" + passage + "\"",
                        "Shared &lt;i&gt;excerpt&lt;/i&gt;")
                .doesNotContain("/documents/" + gone, "<b>linked", "<i>excerpt");
    }

    @Test
    void omitsTheSectionWhenNothingIsSimilarOrSearchIsUnavailable() throws Exception {
        when(search.related(id)).thenReturn(new SearchResult(List.of()));
        assertThat(page()).doesNotContain("Related documents");
        var failure = mock(SearchFailure.class);
        when(failure.reason()).thenReturn(SearchFailure.Reason.DATABASE_UNAVAILABLE);
        when(search.related(id)).thenThrow(failure);
        assertThat(page()).doesNotContain("Related documents").contains("notes.md");
    }

    private SearchResult.Match match(UUID document, UUID passage) {
        return new SearchResult.Match(
                passage == null ? UUID.randomUUID() : passage,
                document,
                1,
                1,
                "Shared <i>excerpt</i>",
                new SearchResult.SourceLocation(1, 0, 1, 10, OptionalInt.empty()),
                0.8);
    }

    private String page() throws Exception {
        return mvc.perform(get("/documents/" + id))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
