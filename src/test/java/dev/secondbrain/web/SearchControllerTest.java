package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.DocumentInspectionService.DocumentSummary;
import dev.secondbrain.documents.DocumentInspectionService.LibraryPage;
import dev.secondbrain.documents.ExtractionState;
import dev.secondbrain.search.SearchFailure;
import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchResult;
import dev.secondbrain.search.SearchScope;
import dev.secondbrain.search.SearchService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

@WebMvcTest(controllers = SearchController.class)
@ExtendWith(OutputCaptureExtension.class)
class SearchControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private SearchService search;
    @MockitoBean private DocumentInspectionService documents;

    @BeforeEach
    void emptyResult() {
        when(search.search(any())).thenReturn(new SearchResult(List.of()));
        when(documents.list(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(
                        invocation -> new LibraryPage(List.of(), invocation.getArgument(0), false));
        when(documents.names(any())).thenReturn(Map.of());
    }

    @Test
    void openingTheFormDoesNotSearchOrPutAQueryInItsAction() throws Exception {
        String html =
                mvc.perform(get("/search"))
                        .andExpect(status().isOk())
                        .andExpect(view().name("search"))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("Referrer-Policy", "no-referrer"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(
                        "method=\"post\"",
                        "action=\"/search\"",
                        "name=\"q\"",
                        "name=\"scope\" value=\"all\"",
                        "embedding provider")
                .doesNotContain("Search completed");
        verifyNoInteractions(search);
    }

    @Test
    void getParametersCannotTriggerInferenceOrEchoPrivateText(CapturedOutput output)
            throws Exception {
        String html =
                mvc.perform(get("/search").param("q", "sentinel-private-query"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html).doesNotContain("sentinel-private-query");
        assertThat(output.getAll()).doesNotContain("sentinel-private-query");
        verifyNoInteractions(search);
    }

    @Test
    void mapsOneWholeLibraryQueryAndRendersTheCountWithoutLoggingText(CapturedOutput output)
            throws Exception {
        var result =
                new SearchResult(
                        List.of(
                                new SearchResult.Match(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        1,
                                        1,
                                        "sentinel-private-excerpt",
                                        new SearchResult.SourceLocation(
                                                1, 0, 1, 5, OptionalInt.empty()),
                                        0.8)));
        when(search.search(any())).thenReturn(result);
        when(documents.names(Set.of(result.matches().getFirst().documentId())))
                .thenReturn(
                        Map.of(
                                result.matches().getFirst().documentId(),
                                "sentinel-private-name.md"));
        String html =
                mvc.perform(submission("  sentinel-private-<script>query</script>  ", "all"))
                        .andExpect(status().isOk())
                        .andExpect(model().attribute("searchResult", result))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("Referrer-Policy", "no-referrer"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        verify(search)
                .search(
                        new SearchQuery(
                                "sentinel-private-<script>query</script>", SearchScope.all()));
        assertThat(html)
                .contains(
                        "Matching passages: 1",
                        "sentinel-private-&lt;script&gt;query&lt;/script&gt;")
                .contains("sentinel-private-excerpt", "sentinel-private-name.md")
                .doesNotContain("<script>");
        assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @Test
    void keepsTheSelectedScopeAndIdsInTheNextSubmission() throws Exception {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        String html =
                mvc.perform(
                                submission("notes", "selected")
                                        .param(
                                                "documentId",
                                                first.toString(),
                                                second.toString(),
                                                first.toString()))
                        .andExpect(status().isOk())
                        .andExpect(model().attribute("scope", "selected"))
                        .andExpect(
                                model().attribute(
                                                "documentIds",
                                                List.of(first, second).stream().sorted().toList()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        verify(search)
                .search(new SearchQuery("notes", SearchScope.selected(Set.of(first, second))));
        assertThat(html)
                .contains(
                        "Selected documents: 2",
                        "name=\"scope\" value=\"selected\"",
                        "name=\"documentId\" value=\"" + first + "\"",
                        "name=\"documentId\" value=\"" + second + "\"");
    }

    @Test
    void anExplicitEmptySelectionNeverBecomesTheWholeLibrary() throws Exception {
        mvc.perform(submission("notes", "selected"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("scope", "selected"))
                .andExpect(model().attribute("documentIds", List.of()));
        verify(search).search(new SearchQuery("notes", SearchScope.selected(Set.of())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "sentinel-private-\u0000", "\uD800"})
    void rejectsInvalidTextBeforeCallingSearch(String text, CapturedOutput output)
            throws Exception {
        reject(submission(text, "all"));
        assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @Test
    void preservesTheUnicodeBudgetWithoutTruncation() throws Exception {
        String maximum = "📚".repeat(1200);
        mvc.perform(submission(maximum, "all")).andExpect(status().isOk());
        verify(search).search(new SearchQuery(maximum, SearchScope.all()));
    }

    @Test
    void rejectsOversizedTextRatherThanTruncatingOrEchoingIt() throws Exception {
        reject(submission("sentinel-private-" + "📚".repeat(1200), "all"));
    }

    @Test
    void rejectsMissingRepeatedAndUnknownScopeOrQueryFields() throws Exception {
        reject(
                post("/search")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("q", "notes"));
        reject(
                post("/search")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("scope", "all"));
        reject(submission("notes", "ALL"));
        reject(submission("notes", "sentinel-private-scope"));
        reject(submission("notes", "all").param("q", "another"));
        reject(submission("notes", "all").param("scope", "selected"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "sentinel-private-id",
                "1-1-1-1-1",
                "00000000-0000-0000-0000-00000000000g"
            })
    void rejectsMalformedSelectedDocumentIdsWithoutInference(String id) throws Exception {
        reject(submission("notes", "selected").param("documentId", id));
    }

    @Test
    void rejectsOverBudgetSelections() throws Exception {
        var ids =
                IntStream.range(0, 101)
                        .mapToObj(i -> UUID.randomUUID().toString())
                        .toArray(String[]::new);
        reject(submission("notes", "selected").param("documentId", ids));
    }

    @Test
    void acceptsTheMaximumSelectedScopeWithoutSilentlyDroppingIds() throws Exception {
        var ids =
                IntStream.range(0, 100)
                        .mapToObj(i -> UUID.randomUUID())
                        .collect(java.util.stream.Collectors.toSet());
        mvc.perform(
                        submission("notes", "selected")
                                .param(
                                        "documentId",
                                        ids.stream().map(UUID::toString).toArray(String[]::new)))
                .andExpect(status().isOk());
        verify(search).search(new SearchQuery("notes", SearchScope.selected(ids)));
    }

    @ParameterizedTest
    @EnumSource(SearchFailure.Reason.class)
    void handlesTypedSearchFailuresWithoutEchoingExceptionsOrInventingResults(
            SearchFailure.Reason reason, CapturedOutput output) throws Exception {
        var failure = mock(SearchFailure.class);
        when(failure.reason()).thenReturn(reason);
        when(failure.getMessage()).thenReturn("sentinel-private-provider");
        when(search.search(any())).thenThrow(failure);
        String html =
                mvc.perform(submission("sentinel-private-query", "all"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(model().attributeDoesNotExist("searchResult"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains("Search could not be completed", "value=\"sentinel-private-query\"")
                .doesNotContain("sentinel-private-provider", "Matching passages:");
        assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @Test
    void wholeLibraryScopeIsExplicitEvenWhenCheckboxSelectionsRemain() throws Exception {
        var id = UUID.randomUUID();
        mvc.perform(submission("notes", "all").param("documentId", id.toString()))
                .andExpect(status().isOk());
        verify(search).search(new SearchQuery("notes", SearchScope.all()));
    }

    @Test
    void rendersEscapedNamesExcerptsAndPhysicalPageReferencesInRankOrder(CapturedOutput output)
            throws Exception {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var pdf =
                new SearchResult.Match(
                        UUID.randomUUID(),
                        first,
                        1,
                        1,
                        "sentinel-private-<script>PDF</script>\nsecond line",
                        new SearchResult.SourceLocation(3, 2, 4, 6, OptionalInt.of(7)),
                        0.9);
        var txt =
                new SearchResult.Match(
                        UUID.randomUUID(),
                        second,
                        1,
                        1,
                        "sentinel-private-<img src=x onerror=bad>",
                        new SearchResult.SourceLocation(1, 0, 1, 9, OptionalInt.empty()),
                        0.7);
        when(search.search(any())).thenReturn(new SearchResult(List.of(pdf, txt)));
        when(documents.names(Set.of(first, second)))
                .thenReturn(
                        Map.of(
                                first,
                                "sentinel-private-<b>notes</b>.pdf",
                                second,
                                "TXT source.txt"));
        var html =
                mvc.perform(submission("sentinel-private-query", "all"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(
                        "sentinel-private-&lt;b&gt;notes&lt;/b&gt;.pdf",
                        "sentinel-private-&lt;script&gt;PDF&lt;/script&gt;\nsecond line",
                        "Page 7",
                        "Lines 3–4",
                        "Lines 1–1",
                        "href=\"/documents/" + first + "/passages/" + pdf.passageId() + "\"",
                        "&lt;img src=x onerror=bad&gt;")
                .doesNotContain("<script>", "<img src=x", "<b>notes");
        assertThat(html.indexOf("PDF&lt;/script&gt;")).isLessThan(html.indexOf("&lt;img src=x"));
        assertThat(output.getAll()).doesNotContain("sentinel-private");
        verify(documents, org.mockito.Mockito.never()).find(any());
    }

    @Test
    void suppressesAnExcerptWhenItsDocumentHasBecomeHidden() throws Exception {
        var id = UUID.randomUUID();
        when(search.search(any()))
                .thenReturn(
                        new SearchResult(
                                List.of(
                                        new SearchResult.Match(
                                                UUID.randomUUID(),
                                                id,
                                                1,
                                                1,
                                                "sentinel-private-hidden",
                                                new SearchResult.SourceLocation(
                                                        1, 0, 1, 3, OptionalInt.empty()),
                                                0.8))));
        var html =
                mvc.perform(submission("notes", "all"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains("Matching passages: 0", "No matching passages in this scope.")
                .doesNotContain("sentinel-private-hidden", "href=\"/documents/" + id);
    }

    @Test
    void browsesPagesWithABlankQueryWithoutInferenceAndKeepsCrossPageSelections(
            CapturedOutput output) throws Exception {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(documents.list(1))
                .thenReturn(
                        new LibraryPage(
                                List.of(
                                        new DocumentSummary(
                                                second,
                                                "sentinel-private-<script>choice</script>.md",
                                                ExtractionState.EXTRACTED,
                                                Instant.EPOCH)),
                                1,
                                false));
        when(documents.names(Set.of(first))).thenReturn(Map.of(first, "Selected source.txt"));
        var html =
                mvc.perform(
                                submission("", "selected")
                                        .param("documentId", first.toString())
                                        .param("page", "0")
                                        .param("action", "next"))
                        .andExpect(status().isOk())
                        .andExpect(model().attributeDoesNotExist("searchResult"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(
                        "Page 2",
                        "Selected on other pages",
                        "Selected source.txt",
                        "sentinel-private-&lt;script&gt;choice&lt;/script&gt;.md",
                        "value=\"previous\"")
                .doesNotContain("<script>", "value=\"next\"");
        assertThat(html).contains("name=\"documentId\" value=\"" + first + "\" checked");
        verifyNoInteractions(search);
        assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @Test
    void removedSelectionsRemainExplicitWithoutExposingOldNames() throws Exception {
        var id = UUID.randomUUID();
        String html =
                mvc.perform(submission("notes", "selected").param("documentId", id.toString()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html).contains("Document unavailable", "Matching passages: 0");
        verify(search).search(new SearchQuery("notes", SearchScope.selected(Set.of(id))));
    }

    @Test
    void pickerAndNameLookupFailuresReturnSafeFeedbackWithoutPartialExcerpts(CapturedOutput output)
            throws Exception {
        when(documents.list(0))
                .thenThrow(
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "sentinel-private-database"));
        String html =
                mvc.perform(get("/search"))
                        .andExpect(status().isServiceUnavailable())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains("Search could not be completed")
                .doesNotContain("sentinel-private");
        verifyNoInteractions(search);
        org.mockito.Mockito.doReturn(new LibraryPage(List.of(), 0, false)).when(documents).list(0);
        when(documents.names(any()))
                .thenAnswer(
                        invocation -> {
                            if (!((Set<?>) invocation.getArgument(0)).isEmpty())
                                throw new org.springframework.dao
                                        .DataAccessResourceFailureException(
                                        "sentinel-private-labels");
                            return Map.of();
                        });
        when(search.search(any()))
                .thenReturn(
                        new SearchResult(
                                List.of(
                                        new SearchResult.Match(
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                1,
                                                1,
                                                "sentinel-private-excerpt",
                                                new SearchResult.SourceLocation(
                                                        1, 0, 1, 3, OptionalInt.empty()),
                                                0.8))));
        html =
                mvc.perform(submission("notes", "all"))
                        .andExpect(status().isServiceUnavailable())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains("Search could not be completed")
                .doesNotContain("sentinel-private-excerpt", "Matching passages:");
        assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @Test
    void rejectsInvalidPagingAndActionsWithoutInference() throws Exception {
        for (var page : List.of("-1", "sentinel-private-page", "2147483648")) {
            mvc.perform(get("/search").param("page", page)).andExpect(status().isBadRequest());
            reject(submission("notes", "selected").param("page", page));
        }
        reject(submission("notes", "selected").param("page", "0").param("action", "previous"));
        reject(submission("notes", "selected").param("page", "2147483647").param("action", "next"));
        reject(submission("notes", "selected").param("action", "unknown"));
        reject(submission("notes", "selected").param("page", "0", "1"));
        reject(submission("notes", "selected").param("action", "search", "next"));
    }

    private void reject(MockHttpServletRequestBuilder request) throws Exception {
        String html =
                mvc.perform(request)
                        .andExpect(status().isBadRequest())
                        .andExpect(model().attributeDoesNotExist("searchResult"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html).contains("Enter a valid query").doesNotContain("sentinel-private");
        verifyNoInteractions(search);
    }

    private static MockHttpServletRequestBuilder submission(String query, String scope) {
        return post("/search")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("q", query)
                .param("scope", scope);
    }
}
