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

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

@WebMvcTest(controllers = SearchController.class)
@ExtendWith(OutputCaptureExtension.class)
class SearchControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private SearchService search;

    @BeforeEach
    void emptyResult() {
        when(search.search(any())).thenReturn(new SearchResult(List.of()));
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
                .doesNotContain("<script>", "sentinel-private-excerpt");
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
    void rejectsAmbiguousAllScopeAndOverBudgetSelections() throws Exception {
        reject(submission("notes", "all").param("documentId", UUID.randomUUID().toString()));
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
