package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.embeddings.EmbeddingFailure;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class RetrievalEvaluationTest {
    private final MockClock timerClock = new MockClock();
    private final SimpleMeterRegistry meters =
            new SimpleMeterRegistry(SimpleConfig.DEFAULT, timerClock);
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final EvaluationBudget.Limits limits = new EvaluationBudget.Limits(11, 4096, 4096);
    private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private ReferenceIndex index;
    private SearchTelemetry telemetry;
    @TempDir Path directory;

    @BeforeEach
    void prepare() throws Exception {
        index = ReferenceIndex.prepare(RetrievalReferenceCollection.load());
        observations
                .observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters));
        telemetry = new SearchTelemetry(meters, observations);
    }

    @AfterEach
    void close() {
        meters.close();
    }

    @Test
    void recordsAllCasesWithDeduplicatedRecallAbsenceSettingsAndRealObservationDurations()
            throws Exception {
        var evaluation = new RetrievalEvaluation(index, limits, clock);
        var calls = new AtomicInteger();
        var budget =
                new EvaluationBudget(
                        EvaluationBudgetTest.provider(meters, calls, 2, true), limits, meters);
        budget.embed(index.documents().stream().map(ReferenceIndex.Document::text).toList());
        var deletions = new ArrayList<List<String>>();
        var queries = new ArrayList<SearchQuery>();
        var number = new AtomicInteger();
        evaluation.run(
                query -> {
                    queries.add(query);
                    var reference = index.collection().cases().get(number.getAndIncrement());
                    try (var operation = telemetry.start()) {
                        if (query.scope() instanceof SearchScope.SelectedDocuments selected
                                && selected.documentIds().isEmpty()) {
                            operation.emptyScope();
                            return new SearchResult(List.of());
                        }
                        try {
                            budget.embed(List.of(query.text()));
                        } catch (InterruptedException interrupted) {
                            throw new AssertionError(interrupted);
                        }
                        var result = matches(reference.expectedSources());
                        timerClock.add(Duration.ofMillis(25));
                        operation.success(result.matches().size());
                        return result;
                    }
                },
                deletions::add,
                budget,
                meters);
        var report = evaluation.report(budget.usage());
        assertThat(report.status()).isEqualTo(RetrievalEvaluation.Status.COMPLETED);
        assertThat(report.cases())
                .hasSize(11)
                .allSatisfy(
                        result -> {
                            assertThat(result.status())
                                    .isEqualTo(RetrievalEvaluation.Status.COMPLETED);
                            assertThat(result.failureCode()).isNull();
                            if (result.expectedPassages().isEmpty()) {
                                assertThat(result.recallAtK()).isNull();
                                assertThat(result.absenceMatched()).isTrue();
                            } else {
                                assertThat(result.recallAtK()).isEqualTo(1.0);
                                assertThat(result.absenceMatched()).isNull();
                            }
                            assertThat(result.durationSeconds())
                                    .isCloseTo(
                                            result.id().equals("empty-selection") ? 0.0 : 0.025,
                                            org.assertj.core.data.Offset.offset(1e-12));
                        });
        assertThat(deletions)
                .containsExactlyElementsOf(
                        index.collection().cases().stream()
                                .map(RetrievalReferenceCollection.QueryCase::deletedDocuments)
                                .toList());
        assertThat(queries)
                .extracting(SearchQuery::scope)
                .containsExactlyElementsOf(
                        index.collection().cases().stream()
                                .map(reference -> index.scopeFor(reference.scope()))
                                .toList());
        assertThat(report.configuration().model()).isEqualTo("@cf/baai/bge-m3");
        assertThat(report.configuration().topK()).isEqualTo(5);
        assertThat(report.configuration().minimumScore()).isEqualTo(0.3);
        assertThat(report.usage()).isEqualTo(new EvaluationBudget.Usage(11, 1258, 11, 11, 22));
        assertThat(report.bindings())
                .filteredOn(binding -> binding.label().equals("arraylist"))
                .singleElement()
                .satisfies(
                        binding ->
                                assertThat(binding.sourceLabels())
                                        .containsExactly(
                                                "arraylist-behavior", "arraylist-removal"));
        var path = directory.resolve("report.json");
        RetrievalEvaluation.write(path, report);
        var json = Files.readString(path);
        var parsed = JsonMapper.builder().build().readTree(json);
        assertThat(parsed.path("cases").size()).isEqualTo(11);
        assertThat(parsed.path("status").stringValue()).isEqualTo("COMPLETED");
        for (var reference : index.collection().cases())
            assertThat(json).doesNotContain(reference.query());
        for (var document : index.documents())
            assertThat(json).doesNotContain(document.text(), document.name());
    }

    @Test
    void separatesPartialRecallAndUnexpectedEvidenceFromExecutionFailure() {
        var evaluation = new RetrievalEvaluation(index, limits, clock);
        var budget = budget();
        evaluation.run(
                query -> {
                    try (var operation = telemetry.start()) {
                        var result = matches(List.of("arraylist-behavior"));
                        operation.success(result.matches().size());
                        return result;
                    }
                },
                labels -> {},
                budget,
                meters);
        var report = evaluation.report(budget.usage());
        assertThat(report.status()).isEqualTo(RetrievalEvaluation.Status.COMPLETED);
        assertThat(find(report, "duplicate-comparison").recallAtK()).isEqualTo(0.5);
        assertThat(find(report, "hashset-filter").recallAtK()).isZero();
        assertThat(find(report, "threads-absent").absenceMatched()).isFalse();
        assertThat(find(report, "removed-arraylist").absenceMatched()).isFalse();
    }

    @Test
    void stopsAtQuotaFailureWithoutFabricatingEmptyEvidenceOrRetryingRemainingCases() {
        var evaluation = new RetrievalEvaluation(index, limits, clock);
        var budget = budget();
        var calls = new AtomicInteger();
        var retry = Instant.parse("2030-01-02T00:00:00Z");
        evaluation.run(
                query -> {
                    calls.incrementAndGet();
                    try (var operation = telemetry.start()) {
                        timerClock.add(Duration.ofMillis(40));
                        operation.failure(SearchFailure.Reason.QUOTA_EXHAUSTED);
                        throw new SearchFailure(SearchFailure.Reason.QUOTA_EXHAUSTED, retry, null);
                    }
                },
                labels -> {},
                budget,
                meters);
        var report = evaluation.report(budget.usage());
        assertThat(calls).hasValue(1);
        assertThat(report.status()).isEqualTo(RetrievalEvaluation.Status.FAILED);
        assertThat(report.failureCode()).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(report.retryAt()).isEqualTo(retry.toString());
        assertThat(report.cases().getFirst())
                .satisfies(
                        result -> {
                            assertThat(result.status())
                                    .isEqualTo(RetrievalEvaluation.Status.FAILED);
                            assertThat(result.durationSeconds()).isEqualTo(0.04);
                            assertThat(result.recallAtK()).isNull();
                            assertThat(result.absenceMatched()).isNull();
                        });
        assertThat(report.cases().subList(1, 11))
                .allSatisfy(
                        result ->
                                assertThat(result.status())
                                        .isEqualTo(RetrievalEvaluation.Status.NOT_RUN));
    }

    @Test
    void preservesBudgetStopThroughQueryEmbeddingFailureTranslation() throws Exception {
        var evaluation = new RetrievalEvaluation(index, limits, clock);
        var budget =
                new EvaluationBudget(
                        EvaluationBudgetTest.provider(meters, new AtomicInteger(), 0, false),
                        new EvaluationBudget.Limits(1, 4096, 4096),
                        meters);
        budget.embed(List.of("already spent"));
        var embedding = new QueryEmbedding(budget);
        evaluation.run(
                query -> {
                    try (var operation = telemetry.start()) {
                        try {
                            embedding.embed(query, EvaluationBudgetTest.MODEL);
                        } catch (SearchFailure failed) {
                            operation.failure(failed.reason());
                            throw failed;
                        }
                        throw new AssertionError("Expected budget refusal");
                    }
                },
                labels -> {},
                budget,
                meters);
        assertThat(evaluation.report(budget.usage()).failureCode()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(budget.usage().attemptedRequests()).isEqualTo(1);
    }

    @Test
    void reportsSetupFailureWithNoInventedCaseMeasurementsOrPrivateExceptionText()
            throws Exception {
        var evaluation = new RetrievalEvaluation(index, limits, clock);
        var budget = budget();
        evaluation.run(
                query -> {
                    throw new IllegalStateException("private provider body and secret");
                },
                labels -> {},
                budget,
                meters);
        var report = evaluation.report(budget.usage());
        assertThat(report.status()).isEqualTo(RetrievalEvaluation.Status.FAILED);
        assertThat(report.cases().getFirst().durationSeconds()).isNull();
        var path = directory.resolve("failed.json");
        RetrievalEvaluation.write(path, report);
        assertThat(Files.readString(path)).doesNotContain("private provider body", "secret");
        assertThat(report.failureCode()).isEqualTo("EVALUATION_UNAVAILABLE");
    }

    @Test
    void recordsEmbeddingSetupFailureSeparatelyFromNeverRunSearchCases() {
        var evaluation = new RetrievalEvaluation(index, limits, clock);
        var failed = org.mockito.Mockito.mock(EmbeddingFailure.class);
        org.mockito.Mockito.when(failed.reason()).thenReturn(EmbeddingFailure.Reason.RATE_LIMITED);
        org.mockito.Mockito.when(failed.retryAt())
                .thenReturn(java.util.Optional.of(Instant.parse("2030-01-01T01:00:00Z")));
        evaluation.embeddingFailed(failed);
        var report = evaluation.report(budget().usage());
        assertThat(report.failureCode()).isEqualTo("RATE_LIMITED");
        assertThat(report.cases())
                .allSatisfy(
                        result -> {
                            assertThat(result.status())
                                    .isEqualTo(RetrievalEvaluation.Status.NOT_RUN);
                            assertThat(result.durationSeconds()).isNull();
                            assertThat(result.recallAtK()).isNull();
                        });
    }

    private EvaluationBudget budget() {
        return new EvaluationBudget(
                EvaluationBudgetTest.provider(meters, new AtomicInteger(), 0, false),
                limits,
                meters);
    }

    private SearchResult matches(List<String> sources) {
        var ids = index.passagesForSources(sources);
        return new SearchResult(
                index.documents().stream()
                        .filter(document -> ids.contains(document.passageId()))
                        .map(
                                document ->
                                        new SearchResult.Match(
                                                document.passageId(),
                                                document.documentId(),
                                                1,
                                                1,
                                                document.text(),
                                                document.location(),
                                                0.9))
                        .toList());
    }

    private static RetrievalEvaluation.CaseResult find(
            RetrievalEvaluation.Report report, String id) {
        return report.cases().stream()
                .filter(result -> result.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
