package dev.secondbrain.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchResult;
import dev.secondbrain.search.SearchScope;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

@ExtendWith(OutputCaptureExtension.class)
class QuestionAnswerServiceTest {
    private static final String PRIVATE = "private-question-prompt-name-text-answer-token-sentinel";
    private final QuestionService generation = mock(QuestionService.class);
    private final DocumentInspectionService documents = mock(DocumentInspectionService.class);
    private final QuestionAnswerService service =
            new QuestionAnswerService(generation, documents, QuestionTelemetry.disabled());
    private final SearchQuery query = new SearchQuery(PRIVATE, SearchScope.all(), 20);

    @Test
    void returnsCitationsInModelOrderWithFreshNamesExactExcerptsAndLocations() {
        var first = match("First unchanged source", OptionalInt.empty());
        var second = match("Second unchanged source", OptionalInt.of(7));
        var uncited = match("Uncited context", OptionalInt.empty());
        ready(List.of(first, second, uncited), List.of(second.passageId(), first.passageId()));
        var answer = service.answer(query);
        assertThat(answer.outcome()).isEqualTo(QuestionAnswerService.Outcome.ANSWERED);
        assertThat(answer.text()).isEqualTo(PRIVATE);
        assertThat(answer.citations()).containsExactly(source(second), source(first));
        assertThat(answer.citations().getFirst().location().pageNumber()).hasValue(7);
        assertThat(answer.citations().getFirst().name()).isEqualTo(PRIVATE + ".pdf");
        verify(documents)
                .sources(
                        Map.of(
                                first.passageId(),
                                first.documentId(),
                                second.passageId(),
                                second.documentId(),
                                uncited.passageId(),
                                uncited.documentId()));
        assertThat(answer.toString() + answer.citations())
                .doesNotContain(
                        PRIVATE, first.passageId().toString(), second.documentId().toString());
        assertThatThrownBy(() -> answer.citations().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "fabricated", "other-question", "duplicate", "omitted"})
    void invalidCitationsNeverReadSourcesOrProduceAnAnswer(String fault) {
        var supplied = match("Supplied evidence", OptionalInt.empty());
        var omitted = match("Real but not supplied evidence", OptionalInt.empty());
        List<UUID> ids =
                switch (fault) {
                    case "missing" -> List.of();
                    case "duplicate" -> List.of(supplied.passageId(), supplied.passageId());
                    case "omitted", "other-question" -> List.of(omitted.passageId());
                    default -> List.of(UUID.randomUUID());
                };
        if (fault.equals("omitted")) {
            var longQuestion = new SearchQuery("📚".repeat(1200), SearchScope.all());
            var noFit = match("📚".repeat(1200), OptionalInt.empty());
            var context =
                    EvidenceContext.select(longQuestion, new SearchResult(List.of(noFit, supplied)))
                            .orElseThrow();
            assertThat(context.sources()).containsExactly(supplied);
            when(generation.generate(query))
                    .thenReturn(
                            new QuestionService.Candidate(
                                    new ChatProvider.Reply(
                                            ChatProvider.Outcome.ANSWERED,
                                            PRIVATE,
                                            List.of(noFit.passageId())),
                                    Optional.of(context)));
        } else candidate(List.of(supplied), ids, ChatProvider.Outcome.ANSWERED);
        var expected =
                fault.equals("missing")
                        ? QuestionFailure.Reason.MISSING_CITATIONS
                        : fault.equals("duplicate")
                                ? QuestionFailure.Reason.DUPLICATE_CITATION
                                : QuestionFailure.Reason.INVALID_CITATION;
        assertFailure(expected, QuestionFailure.Stage.CITATIONS);
        verifyNoInteractions(documents);
    }

    @Test
    void noContextReturnsInsufficientInformationWithoutSourceReads() {
        when(generation.generate(query)).thenReturn(QuestionService.Candidate.withoutEvidence());
        var result = service.answer(query);
        assertThat(result.outcome())
                .isEqualTo(QuestionAnswerService.Outcome.INSUFFICIENT_INFORMATION);
        assertThat(result.text()).isEmpty();
        assertThat(result.citations()).isEmpty();
        verifyNoInteractions(documents);
    }

    @Test
    void modelAbsenceStillChecksAllSuppliedEvidenceButReturnsNoExcerpt() {
        var source = match(PRIVATE, OptionalInt.of(3));
        candidate(List.of(source), List.of(), ChatProvider.Outcome.INSUFFICIENT_INFORMATION);
        when(documents.sources(any())).thenReturn(Map.of(source.passageId(), source(source)));
        var answer = service.answer(query);
        assertThat(answer.outcome())
                .isEqualTo(QuestionAnswerService.Outcome.INSUFFICIENT_INFORMATION);
        assertThat(answer.citations()).isEmpty();
        verify(documents).sources(Map.of(source.passageId(), source.documentId()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing-cited",
                "missing-uncited",
                "extra",
                "owner",
                "passage",
                "version",
                "text",
                "line",
                "column",
                "end-line",
                "end-column",
                "page",
                "null"
            })
    void anyChangedOrUnavailableEvidenceDiscardsTheEntireAnswer(String fault) {
        var first = match(PRIVATE, OptionalInt.of(3));
        var second = match(PRIVATE, OptionalInt.empty());
        ready(List.of(first, second), List.of(first.passageId()));
        var original = source(first);
        var location = original.location();
        var changed =
                switch (fault) {
                    case "owner" ->
                            new DocumentInspectionService.PassageSource(
                                    UUID.randomUUID(),
                                    first.passageId(),
                                    2,
                                    PRIVATE,
                                    first.text(),
                                    location);
                    case "passage" ->
                            new DocumentInspectionService.PassageSource(
                                    first.documentId(),
                                    UUID.randomUUID(),
                                    2,
                                    PRIVATE,
                                    first.text(),
                                    location);
                    case "version" ->
                            new DocumentInspectionService.PassageSource(
                                    first.documentId(),
                                    first.passageId(),
                                    3,
                                    PRIVATE,
                                    first.text(),
                                    location);
                    case "text" ->
                            new DocumentInspectionService.PassageSource(
                                    first.documentId(),
                                    first.passageId(),
                                    2,
                                    PRIVATE,
                                    "Changed",
                                    location);
                    case "line", "column", "end-line", "end-column", "page" ->
                            new DocumentInspectionService.PassageSource(
                                    first.documentId(),
                                    first.passageId(),
                                    2,
                                    PRIVATE,
                                    first.text(),
                                    new DocumentInspectionService.SourceLocation(
                                            fault.equals("line") ? 2 : 1,
                                            fault.equals("column") ? 2 : 0,
                                            fault.equals("end-line") ? 4 : 3,
                                            fault.equals("end-column") ? 4 : 5,
                                            fault.equals("page")
                                                    ? OptionalInt.of(4)
                                                    : OptionalInt.of(3)));
                    default -> original;
                };
        Map<UUID, DocumentInspectionService.PassageSource> visible =
                switch (fault) {
                    case "missing-cited" -> Map.of(second.passageId(), source(second));
                    case "missing-uncited" -> Map.of(first.passageId(), original);
                    case "extra" ->
                            Map.of(
                                    first.passageId(),
                                    original,
                                    second.passageId(),
                                    source(second),
                                    UUID.randomUUID(),
                                    original);
                    case "null" -> null;
                    default ->
                            Map.of(first.passageId(), changed, second.passageId(), source(second));
                };
        when(documents.sources(any())).thenReturn(visible);
        assertFailure(QuestionFailure.Reason.SOURCE_UNAVAILABLE, QuestionFailure.Stage.SOURCES);
    }

    @Test
    void validIdsAloneCannotDetectAFactuallyUnsupportedAnswer() {
        var source = match("HashSet does not permit duplicate elements.", OptionalInt.empty());
        ready(List.of(source), List.of(source.passageId()));
        var context =
                EvidenceContext.select(query, new SearchResult(List.of(source))).orElseThrow();
        when(generation.generate(query))
                .thenReturn(
                        new QuestionService.Candidate(
                                new ChatProvider.Reply(
                                        ChatProvider.Outcome.ANSWERED,
                                        "HashSet permits duplicate elements.",
                                        List.of(source.passageId())),
                                Optional.of(context)));
        var result = service.answer(query);
        assertThat(result.outcome()).isEqualTo(QuestionAnswerService.Outcome.ANSWERED);
        assertThat(result.text()).isEqualTo("HashSet permits duplicate elements.");
        assertThat(result.citations().getFirst().text()).isEqualTo(source.text());
        // Identity/visibility acceptance deliberately cannot establish that this claim is true.
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sourceFailuresStaySafeAndDoNotReturnTheCandidate(boolean database) {
        var source = match(PRIVATE, OptionalInt.empty());
        ready(List.of(source), List.of(source.passageId()));
        when(documents.sources(any()))
                .thenThrow(
                        database
                                ? new DataAccessResourceFailureException(PRIVATE)
                                : new IllegalStateException(PRIVATE));
        assertFailure(
                database
                        ? QuestionFailure.Reason.DATABASE_UNAVAILABLE
                        : QuestionFailure.Reason.SOURCE_UNAVAILABLE,
                QuestionFailure.Stage.SOURCES);
    }

    @Test
    void generationFailureIsPreservedWithoutAnySourceRead() {
        var failure =
                new QuestionFailure(
                        QuestionFailure.Stage.GENERATION,
                        QuestionFailure.Reason.QUOTA_EXHAUSTED,
                        null,
                        new RuntimeException(PRIVATE));
        when(generation.generate(query)).thenThrow(failure);
        assertThatThrownBy(() -> service.answer(query)).isSameAs(failure);
        verifyNoInteractions(documents);
    }

    @Test
    void brokenMetricsCannotBypassCitationValidationOrReplaceAnAcceptedAnswer(
            CapturedOutput output) {
        var broken = mock(MeterRegistry.class);
        when(broken.counter(any(String.class), any(String[].class)))
                .thenThrow(new IllegalStateException(PRIVATE));
        var tested =
                new QuestionAnswerService(
                        generation,
                        documents,
                        new QuestionTelemetry(broken, ObservationRegistry.NOOP));
        var source = match(PRIVATE, OptionalInt.empty());
        ready(List.of(source), List.of(source.passageId()));
        assertThat(tested.answer(query).outcome())
                .isEqualTo(QuestionAnswerService.Outcome.ANSWERED);
        candidate(List.of(source), List.of(UUID.randomUUID()), ChatProvider.Outcome.ANSWERED);
        assertThatThrownBy(() -> tested.answer(query))
                .isInstanceOfSatisfying(
                        QuestionFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(QuestionFailure.Reason.INVALID_CITATION));
        assertThat(output).contains("Question telemetry unavailable").doesNotContain(PRIVATE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationAfterGenerationOrSourceReadCannotReturnAnAnswer(boolean duringRead) {
        var source = match(PRIVATE, OptionalInt.empty());
        ready(List.of(source), List.of(source.passageId()));
        if (duringRead)
            when(documents.sources(any()))
                    .thenAnswer(
                            call -> {
                                Thread.currentThread().interrupt();
                                return Map.of(source.passageId(), source(source));
                            });
        else
            when(generation.generate(query))
                    .thenAnswer(
                            call -> {
                                Thread.currentThread().interrupt();
                                return candidateValue(
                                        List.of(source),
                                        List.of(source.passageId()),
                                        ChatProvider.Outcome.ANSWERED);
                            });
        try {
            assertFailure(
                    QuestionFailure.Reason.INTERRUPTED,
                    duringRead ? QuestionFailure.Stage.SOURCES : QuestionFailure.Stage.CITATIONS);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            if (!duringRead) verifyNoInteractions(documents);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void activeCallerTransactionsAreRejectedBeforeGenerationOrResolution() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.answer(query))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(generation, documents);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void answerProjectionCopiesSourcesAndEnforcesSourcedAndInsufficientShapes() {
        var source = source(match(PRIVATE, OptionalInt.empty()));
        var mutable = new ArrayList<>(List.of(source));
        var answer =
                new QuestionAnswerService.Answer(
                        QuestionAnswerService.Outcome.ANSWERED, PRIVATE, mutable);
        mutable.clear();
        assertThat(answer.citations()).containsExactly(source);
        assertThatThrownBy(
                        () ->
                                new QuestionAnswerService.Answer(
                                        QuestionAnswerService.Outcome.ANSWERED, PRIVATE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new QuestionAnswerService.Answer(
                                        QuestionAnswerService.Outcome.ANSWERED,
                                        PRIVATE,
                                        List.of(source, source)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new QuestionAnswerService.Answer(
                                        QuestionAnswerService.Outcome.INSUFFICIENT_INFORMATION,
                                        PRIVATE,
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        for (var text : List.of(" ", "\uD800", "bad\u0000", "x".repeat(8001)))
            assertThatThrownBy(
                            () ->
                                    new QuestionAnswerService.Answer(
                                            QuestionAnswerService.Outcome.ANSWERED,
                                            text,
                                            List.of(source)))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "answered",
                "insufficient_information",
                "missing_citations",
                "invalid_citation",
                "duplicate_citation",
                "source_unavailable"
            })
    void recordsSafeFinalOutcomesAndCallerTraceWithoutContent(
            String expected, CapturedOutput output) {
        var meters = new SimpleMeterRegistry();
        var observations = ObservationRegistry.create();
        observations
                .observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters));
        var telemetry = new QuestionTelemetry(meters, observations);
        var tested = new QuestionAnswerService(generation, documents, telemetry);
        var source = match(PRIVATE, OptionalInt.of(3));
        ready(List.of(source), List.of(source.passageId()));
        if (expected.equals("insufficient_information"))
            candidate(List.of(source), List.of(), ChatProvider.Outcome.INSUFFICIENT_INFORMATION);
        if (expected.equals("missing_citations"))
            candidate(List.of(source), List.of(), ChatProvider.Outcome.ANSWERED);
        if (expected.equals("invalid_citation"))
            candidate(List.of(source), List.of(UUID.randomUUID()), ChatProvider.Outcome.ANSWERED);
        if (expected.equals("duplicate_citation"))
            candidate(
                    List.of(source),
                    List.of(source.passageId(), source.passageId()),
                    ChatProvider.Outcome.ANSWERED);
        if (expected.equals("source_unavailable"))
            when(documents.sources(any())).thenReturn(Map.of());
        var capture = InMemorySpanExporter.create();
        try (var sdk =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(capture))
                        .build()) {
            var tracer =
                    new OtelTracer(sdk.get("test"), new OtelCurrentTraceContext(), event -> {});
            observations
                    .observationConfig()
                    .observationHandler(new DefaultTracingObservationHandler(tracer));
            var caller = tracer.nextSpan().name("caller").start();
            var scope = tracer.withSpan(caller);
            try (scope) {
                if (List.of("answered", "insufficient_information").contains(expected))
                    tested.answer(query);
                else
                    assertThatThrownBy(() -> tested.answer(query))
                            .isInstanceOf(QuestionFailure.class);
                assertThat(tracer.currentSpan().context().spanId())
                        .isEqualTo(caller.context().spanId());
            } finally {
                caller.end();
            }
            var span =
                    capture.getFinishedSpanItems().stream()
                            .filter(value -> value.getName().equals("question.answer"))
                            .findFirst()
                            .orElseThrow();
            assertThat(span.getParentSpanId()).isEqualTo(caller.context().spanId());
            assertThat(span.getTraceId()).isEqualTo(caller.context().traceId());
            assertThat(span.getEvents()).isEmpty();
            assertThat(span.getAttributes().toString()).contains(expected).doesNotContain(PRIVATE);
            assertThat(
                            meters.get("secondbrain.question.answers")
                                    .tag("outcome", expected)
                                    .counter()
                                    .count())
                    .isEqualTo(1);
            assertThat(
                            meters.get("secondbrain.question.answer.duration")
                                    .tag("outcome", expected)
                                    .timer()
                                    .count())
                    .isEqualTo(1);
            assertThat(meters.getMeters())
                    .allSatisfy(
                            meter ->
                                    assertThat(meter.getId().getTags())
                                            .allSatisfy(
                                                    tag ->
                                                            assertThat(tag.getKey())
                                                                    .isIn(
                                                                            "outcome", "error",
                                                                            "kind")));
            assertThat(output)
                    .doesNotContain(
                            PRIVATE, source.documentId().toString(), source.passageId().toString());
        } finally {
            meters.close();
        }
    }

    private void ready(List<SearchResult.Match> sources, List<UUID> citations) {
        candidate(sources, citations, ChatProvider.Outcome.ANSWERED);
        var visible = new java.util.HashMap<UUID, DocumentInspectionService.PassageSource>();
        for (var source : sources) visible.put(source.passageId(), source(source));
        when(documents.sources(any())).thenReturn(visible);
    }

    private void candidate(
            List<SearchResult.Match> sources, List<UUID> citations, ChatProvider.Outcome outcome) {
        when(generation.generate(query)).thenReturn(candidateValue(sources, citations, outcome));
    }

    private QuestionService.Candidate candidateValue(
            List<SearchResult.Match> sources, List<UUID> citations, ChatProvider.Outcome outcome) {
        return new QuestionService.Candidate(
                new ChatProvider.Reply(
                        outcome,
                        outcome == ChatProvider.Outcome.ANSWERED ? PRIVATE : "",
                        citations),
                EvidenceContext.select(query, new SearchResult(sources)));
    }

    private static SearchResult.Match match(String text, OptionalInt page) {
        return new SearchResult.Match(
                UUID.randomUUID(),
                UUID.randomUUID(),
                2,
                1,
                text,
                new SearchResult.SourceLocation(1, 0, 3, 5, page),
                0.9);
    }

    private static DocumentInspectionService.PassageSource source(SearchResult.Match match) {
        var loc = match.location();
        return new DocumentInspectionService.PassageSource(
                match.documentId(),
                match.passageId(),
                match.indexingVersion(),
                PRIVATE + ".pdf",
                match.text(),
                new DocumentInspectionService.SourceLocation(
                        loc.startLine(),
                        loc.startColumn(),
                        loc.endLine(),
                        loc.endColumn(),
                        loc.pageNumber()));
    }

    private void assertFailure(QuestionFailure.Reason reason, QuestionFailure.Stage stage) {
        assertThatThrownBy(() -> service.answer(query))
                .isInstanceOfSatisfying(
                        QuestionFailure.class,
                        failure -> {
                            assertThat(failure.reason()).isEqualTo(reason);
                            assertThat(failure.stage()).isEqualTo(stage);
                            assertThat(failure.getCause()).isNull();
                            var printed = new StringWriter();
                            failure.printStackTrace(new PrintWriter(printed));
                            assertThat(printed.toString()).doesNotContain(PRIVATE);
                        });
    }
}
