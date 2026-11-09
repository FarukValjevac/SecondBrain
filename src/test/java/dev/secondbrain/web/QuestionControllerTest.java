package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import dev.secondbrain.questions.QuestionAnswerService;
import dev.secondbrain.questions.QuestionFailure;
import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchScope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

@WebMvcTest(
        controllers = {QuestionController.class, DocumentSourceController.class},
        properties = "spring.config.import=")
@ExtendWith(OutputCaptureExtension.class)
class QuestionControllerTest {
    private static final String PRIVATE = "question-private-sentinel";
    @Autowired private MockMvc mvc;
    @MockitoBean private QuestionAnswerService answers;
    @MockitoBean private DocumentInspectionService documents;

    @BeforeEach
    void emptyChoicesAndInsufficientAnswer() {
        when(documents.list(anyInt()))
                .thenAnswer(
                        call ->
                                new DocumentInspectionService.LibraryPage(
                                        List.of(), call.getArgument(0), false));
        when(documents.names(any())).thenReturn(Map.of());
        when(answers.answer(any()))
                .thenReturn(
                        new QuestionAnswerService.Answer(
                                QuestionAnswerService.Outcome.INSUFFICIENT_INFORMATION,
                                "",
                                List.of()));
    }

    @Test
    void getIgnoresQuestionParametersAndRendersAnAccessiblePrivatePostFormWithoutInference(
            CapturedOutput output) throws Exception {
        var result =
                mvc.perform(get("/questions").param("q", PRIVATE))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("Referrer-Policy", "no-referrer"))
                        .andExpect(view().name("questions"))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .contains(
                        "action=\"/questions\"",
                        "method=\"post\"",
                        "textarea",
                        "question-help",
                        "name=\"scope\" value=\"all\"",
                        "configured provider")
                .doesNotContain(PRIVATE, "id=\"answer-title\"");
        assertThat(result.getRequest().getSession(false)).isNull();
        verifyNoInteractions(answers);
        assertThat(output).doesNotContain(PRIVATE);
    }

    @Test
    void rendersEscapedQuestionAnswerSourceAndPhysicalPageAndFollowsTheStableSourceLink(
            CapturedOutput output) throws Exception {
        var owner = UUID.randomUUID();
        var passage = UUID.randomUUID();
        var source =
                new DocumentInspectionService.PassageSource(
                        owner,
                        passage,
                        2,
                        PRIVATE + "<img src=x>.pdf",
                        PRIVATE + "<script>source()</script>\nsecond line",
                        new DocumentInspectionService.SourceLocation(
                                3, 2, 5, 8, OptionalInt.of(7)));
        var answer =
                new QuestionAnswerService.Answer(
                        QuestionAnswerService.Outcome.ANSWERED,
                        PRIVATE + "<script>answer()</script>\nnext line",
                        List.of(source));
        when(answers.answer(any())).thenReturn(answer);
        when(documents.source(owner, passage))
                .thenReturn(Optional.of(source))
                .thenReturn(Optional.empty());
        var result =
                mvc.perform(
                                submission(
                                        "  " + PRIVATE + "</textarea><script>question()</script>  ",
                                        "all"))
                        .andExpect(status().isOk())
                        .andExpect(model().attribute("questionAnswer", answer))
                        .andExpect(
                                header().string(
                                                "X-Correlation-ID",
                                                org.hamcrest.Matchers.matchesPattern(
                                                        "[0-9a-f-]{36}")))
                        .andReturn();
        var html = result.getResponse().getContentAsString();
        assertThat(html)
                .contains(
                        "id=\"answer-title\"",
                        "Whole library",
                        "Answer using the whole library",
                        "Page 7",
                        "Lines 3–5",
                        "&lt;script&gt;answer()&lt;/script&gt;",
                        "&lt;script&gt;source()&lt;/script&gt;",
                        "&lt;/textarea&gt;&lt;script&gt;question()&lt;/script&gt;",
                        "&lt;img src=x&gt;.pdf",
                        "href=\"/documents/" + owner + "/passages/" + passage + "\"")
                .doesNotContain("<script>", "<img src=x>", "th:replace", ";jsessionid=");
        assertThat(html.indexOf("id=\"answer-title\""))
                .isLessThan(html.indexOf("id=\"question-text\""));
        assertThat(html)
                .contains(
                        "Your question",
                        "Scope: Whole library",
                        "Ask another question",
                        "target=\"_blank\"",
                        "rel=\"noopener noreferrer\"");
        verify(answers)
                .answer(
                        new SearchQuery(
                                PRIVATE + "</textarea><script>question()</script>",
                                SearchScope.all()));
        var path = "/documents/" + owner + "/passages/" + passage;
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        var gone =
                mvc.perform(get(path))
                        .andExpect(status().isNotFound())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(gone).contains("Source unavailable").doesNotContain(PRIVATE);
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(output).doesNotContain(PRIVATE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesSelectedScopeAndDistinguishesEmptySelectionFromInsufficientEvidence(boolean empty)
            throws Exception {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var request = submission(PRIVATE, "selected");
        if (!empty)
            request.param("documentId", first.toString(), second.toString(), first.toString());
        var result =
                mvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(model().attributeDoesNotExist("questionAnswer"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        verify(answers)
                .answer(
                        new SearchQuery(
                                PRIVATE,
                                SearchScope.selected(empty ? Set.of() : Set.of(first, second))));
        assertThat(result)
                .contains(
                        empty ? "No documents selected" : "Insufficient information",
                        "Selected documents: " + (empty ? 0 : 2))
                .doesNotContain("id=\"answer-title\"", "id=\"answer-sources-title\"");
    }

    @Test
    void checkedBoxesDoNotRestrictAnExplicitWholeLibraryQuestion() throws Exception {
        mvc.perform(submission(PRIVATE, "all").param("documentId", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        verify(answers).answer(new SearchQuery(PRIVATE, SearchScope.all()));
    }

    @Test
    void pagesThroughChoicesWithABlankQuestionWithoutCallingTheAnswerService() throws Exception {
        var id = UUID.randomUUID();
        when(documents.names(Set.of(id))).thenReturn(Map.of(id, PRIVATE + "<script>.md"));
        var html =
                mvc.perform(
                                submission("", "selected")
                                        .param("documentId", id.toString())
                                        .param("action", "next")
                                        .param("page", "0"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(
                        "Page 2",
                        id.toString(),
                        PRIVATE + "&lt;script&gt;.md",
                        "Selected documents: 1");
        verify(documents).list(1);
        verifyNoInteractions(answers);
    }

    @Test
    void blankQuestionRetainsSelectionBeforeAnyDatabaseOrProviderIo() throws Exception {
        var id = UUID.randomUUID();
        var html =
                mvc.perform(
                                submission(" ", "selected")
                                        .param("documentId", id.toString())
                                        .param("page", "2"))
                        .andExpect(status().isBadRequest())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(
                        "Check your question",
                        id.toString(),
                        "aria-invalid=\"true\"",
                        "Selected documents: 1");
        verifyNoInteractions(documents, answers);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "scope",
                "id",
                "page",
                "repeated-query",
                "repeated-scope",
                "action",
                "previous",
                "next-overflow",
                "too-many",
                "oversized"
            })
    void rejectsMalformedOrAmbiguousFormsWithoutSilentlyExpandingScope(
            String fault, CapturedOutput output) throws Exception {
        var request = submission(PRIVATE, "selected");
        switch (fault) {
            case "scope" -> request.param("scope", "foreign");
            case "id" -> request.param("documentId", "1-1-1-1-1");
            case "page" -> request.param("page", "-1");
            case "repeated-query" -> request.param("q", "other");
            case "repeated-scope" -> request.param("scope", "all");
            case "action" -> request.param("action", "search");
            case "previous" -> request.param("action", "previous");
            case "next-overflow" ->
                    request.param("page", Integer.toString(Integer.MAX_VALUE))
                            .param("action", "next");
            case "too-many" ->
                    request.param(
                            "documentId",
                            java.util.stream.IntStream.range(0, 101)
                                    .mapToObj(i -> new UUID(0, i).toString())
                                    .toArray(String[]::new));
            case "oversized" -> request = submission("q".repeat(1201), "selected");
            default -> throw new AssertionError("Unknown fixture");
        }
        var html =
                mvc.perform(request)
                        .andExpect(status().isBadRequest())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains("Check the question form", "Selected documents: 0")
                .doesNotContain(PRIVATE, "Answer using the whole library");
        verifyNoInteractions(answers, documents);
        assertThat(output).doesNotContain(PRIVATE);
    }

    @ParameterizedTest
    @CsvSource({
        "NO_COMPATIBLE_INDEX,200,No searchable documents",
        "DATABASE_UNAVAILABLE,503,Document storage unavailable",
        "INCOMPATIBLE_MODEL,503,Question configuration needs attention",
        "INVALID_EMBEDDING,503,Unusable provider response",
        "INVALID_EVIDENCE,409,Answer sources changed",
        "MISSING_CITATIONS,503,Answer sources could not be verified",
        "INVALID_CITATION,503,Answer sources could not be verified",
        "DUPLICATE_CITATION,503,Answer sources could not be verified",
        "SOURCE_UNAVAILABLE,409,Answer sources changed",
        "ACCESS_DENIED,503,Question configuration needs attention",
        "MODEL_UNAVAILABLE,503,Question configuration needs attention",
        "INVALID_INPUT,503,Question configuration needs attention",
        "INVALID_RESPONSE,503,Unusable provider response",
        "OUTPUT_LIMIT,503,Answer was cut short",
        "RATE_LIMITED,429,Too many requests",
        "QUOTA_EXHAUSTED,429,Free allowance exhausted",
        "UNAVAILABLE,503,Answer provider unavailable",
        "TIMED_OUT,504,Question timed out",
        "INTERRUPTED,503,Question interrupted"
    })
    void mapsEverySafeFailureWithoutRenderingAnAnswerOrLeakingProviderDetails(
            QuestionFailure.Reason reason, int code, String title, CapturedOutput output)
            throws Exception {
        var failure = mock(QuestionFailure.class);
        var hint = Instant.parse("2030-01-02T00:00:00Z");
        when(failure.reason()).thenReturn(reason);
        when(failure.retryAt()).thenReturn(Optional.of(hint));
        when(failure.getMessage()).thenReturn("provider-private-token-answer-sentinel");
        when(answers.answer(any())).thenThrow(failure);
        var id = UUID.randomUUID();
        var html =
                mvc.perform(submission(PRIVATE, "selected").param("documentId", id.toString()))
                        .andExpect(status().is(code))
                        .andExpect(model().attributeDoesNotExist("questionAnswer"))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("Referrer-Policy", "no-referrer"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(title, PRIVATE, id.toString(), "Selected documents: 1")
                .doesNotContain(
                        "provider-private-token-answer-sentinel",
                        "id=\"answer-title\"",
                        "id=\"answer-sources-title\"");
        if (Set.of(
                        QuestionFailure.Reason.RATE_LIMITED,
                        QuestionFailure.Reason.QUOTA_EXHAUSTED,
                        QuestionFailure.Reason.UNAVAILABLE)
                .contains(reason)) assertThat(html).contains(hint.toString(), "(UTC)");
        else assertThat(html).doesNotContain(hint.toString());
        assertThat(output).doesNotContain(PRIVATE, "provider-private-token-answer-sentinel");
    }

    @Test
    void pickerFailurePreservesValidStateAndDoesNotGenerate(CapturedOutput output)
            throws Exception {
        when(documents.list(anyInt()))
                .thenThrow(
                        new DataAccessResourceFailureException("provider-private-token-sentinel"));
        var id = UUID.randomUUID();
        var html =
                mvc.perform(submission(PRIVATE, "selected").param("documentId", id.toString()))
                        .andExpect(status().isServiceUnavailable())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(
                        "Document storage unavailable",
                        PRIVATE,
                        id.toString(),
                        "Retained selections")
                .doesNotContain("provider-private-token-sentinel");
        verifyNoInteractions(answers);
        assertThat(output).doesNotContain(PRIVATE, "provider-private-token-sentinel");
    }

    private static MockHttpServletRequestBuilder submission(String question, String scope) {
        return post("/questions")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("q", question)
                .param("scope", scope);
    }
}
