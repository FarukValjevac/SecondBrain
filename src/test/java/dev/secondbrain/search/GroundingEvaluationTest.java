package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.secondbrain.questions.QuestionAnswerService;
import dev.secondbrain.questions.QuestionFailure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class GroundingEvaluationTest {
    @Test
    void quotaStopsTheRunWithoutTurningLaterCasesIntoAbsenceOrRetrying() throws Exception {
        var index = ReferenceIndex.prepare(RetrievalReferenceCollection.loadRelease());
        var evaluation = new GroundingEvaluation(index, Clock.systemUTC());
        var failure = mock(QuestionFailure.class);
        when(failure.reason()).thenReturn(QuestionFailure.Reason.QUOTA_EXHAUSTED);
        var meters = new SimpleMeterRegistry();
        try {
            var calls = new AtomicInteger();
            var embeddings =
                    new EvaluationBudget(
                            EvaluationBudgetTest.provider(meters, new AtomicInteger(), 0, false),
                            new EvaluationBudget.Limits(22, 8192, 8192),
                            meters);
            var chat =
                    new GroundingBudget(
                            r -> {
                                throw new AssertionError();
                            },
                            new GroundingBudget.Limits(20, 160000, 512, 10240, 100000),
                            meters);
            evaluation.run(
                    q -> {
                        calls.incrementAndGet();
                        throw failure;
                    },
                    labels -> {},
                    chat,
                    embeddings);
            var report = evaluation.report(chat.usage());
            assertThat(calls).hasValue(1);
            assertThat(report.status()).isEqualTo(GroundingEvaluation.Status.INCOMPLETE);
            assertThat(report.failureCode()).isEqualTo("QUOTA_EXHAUSTED");
            assertThat(report.cases().getFirst().outcome()).isNull();
            var json =
                    tools.jackson.databind.json.JsonMapper.builder()
                            .build()
                            .writeValueAsString(report);
            assertThat(json).doesNotContain(index.collection().cases().getFirst().query());
            assertThat(report.cases().subList(1, 20))
                    .allSatisfy(
                            c ->
                                    assertThat(c.status())
                                            .isEqualTo(RetrievalEvaluation.Status.NOT_RUN));
            assertThat(ReleaseQualityGates.grounding(index, report, List.of()).verdict())
                    .isEqualTo(ReleaseQualityGates.Verdict.NOT_RUN);
        } finally {
            meters.close();
        }
    }

    @Test
    void completedExecutionWithoutReviewIsNotGroundingSuccess() throws Exception {
        var index = ReferenceIndex.prepare(RetrievalReferenceCollection.loadRelease());
        var evaluation = new GroundingEvaluation(index, Clock.systemUTC());
        var meters = new SimpleMeterRegistry();
        try {
            var embeddings =
                    new EvaluationBudget(
                            EvaluationBudgetTest.provider(meters, new AtomicInteger(), 0, false),
                            new EvaluationBudget.Limits(22, 8192, 8192),
                            meters);
            var chat =
                    new GroundingBudget(
                            r -> {
                                throw new AssertionError();
                            },
                            new GroundingBudget.Limits(20, 160000, 512, 10240, 100000),
                            meters);
            evaluation.run(
                    q ->
                            new QuestionAnswerService.Answer(
                                    QuestionAnswerService.Outcome.INSUFFICIENT_INFORMATION,
                                    "",
                                    List.of()),
                    labels -> {},
                    chat,
                    embeddings);
            var report = evaluation.report(chat.usage());
            assertThat(report.status()).isEqualTo(GroundingEvaluation.Status.COMPLETED);
            assertThat(report.cases()).hasSize(20);
            assertThat(chat.usage().attemptedRequests()).isZero();
            assertThat(ReleaseQualityGates.grounding(index, report, List.of()).verdict())
                    .isEqualTo(ReleaseQualityGates.Verdict.NOT_RUN);
        } finally {
            meters.close();
        }
    }
}
