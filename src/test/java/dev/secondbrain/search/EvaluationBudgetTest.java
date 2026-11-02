package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

class EvaluationBudgetTest {
    static final EmbeddingModel MODEL = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);

    @Test
    void requiresLiteralConsentAndExplicitNumericBudgets() {
        var properties = new Properties();
        assertThatThrownBy(() -> EvaluationBudget.Limits.from(properties))
                .isInstanceOf(IllegalArgumentException.class);
        properties.setProperty("evaluation.allowLive", "true");
        assertThatThrownBy(() -> EvaluationBudget.Limits.from(properties))
                .isInstanceOf(IllegalArgumentException.class);
        properties.setProperty("evaluation.maxRequests", "11");
        properties.setProperty("evaluation.maxInputBytes", "4096");
        properties.setProperty("evaluation.maxReportedTokens", "4096");
        assertThat(EvaluationBudget.Limits.from(properties))
                .isEqualTo(new EvaluationBudget.Limits(11, 4096, 4096));
        properties.setProperty("evaluation.allowLive", "TRUE");
        assertThatThrownBy(() -> EvaluationBudget.Limits.from(properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "0,4096,4096",
        "33,4096,4096",
        "11,0,4096",
        "11,16385,4096",
        "11,4096,0",
        "11,4096,8193"
    })
    void rejectsUnboundedOrInvalidAllowances(int calls, long bytes, long tokens) {
        assertThatThrownBy(() -> new EvaluationBudget.Limits(calls, bytes, tokens))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checksTheWholeDeclaredRunBeforeAnyProviderIsNeeded() throws Exception {
        var index = ReferenceIndex.prepare(RetrievalReferenceCollection.load());
        EvaluationBudget.preflight(index, new EvaluationBudget.Limits(11, 1258, 4096));
        assertThatThrownBy(
                        () ->
                                EvaluationBudget.preflight(
                                        index, new EvaluationBudget.Limits(10, 1258, 4096)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                EvaluationBudget.preflight(
                                        index, new EvaluationBudget.Limits(11, 1257, 4096)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reservesActualUtf8BytesAndRefusesFurtherRequestsBeforeIo() throws Exception {
        var meters = new SimpleMeterRegistry();
        try {
            var calls = new AtomicInteger();
            var budget =
                    new EvaluationBudget(
                            provider(meters, calls, 0, false),
                            new EvaluationBudget.Limits(2, 5, 10),
                            meters);
            budget.embed(List.of("é"));
            budget.embed(List.of("abc"));
            assertThat(budget.usage().submittedInputBytes()).isEqualTo(5);
            assertThatThrownBy(() -> budget.embed(List.of("x")))
                    .isInstanceOf(EvaluationBudget.LimitReached.class);
            assertThat(calls).hasValue(2);
            assertThat(budget.usage().attemptedRequests()).isEqualTo(2);
        } finally {
            meters.close();
        }
    }

    @Test
    void failedAmbiguousAttemptsStillConsumeTheRequestAndByteAllowance() {
        var meters = new SimpleMeterRegistry();
        try {
            var calls = new AtomicInteger();
            EmbeddingProvider failed =
                    new EmbeddingProvider() {
                        public EmbeddingModel model() {
                            return MODEL;
                        }

                        public List<EmbeddingVector> embed(List<String> inputs) {
                            calls.incrementAndGet();
                            throw new IllegalStateException("private transport details");
                        }
                    };
            var budget =
                    new EvaluationBudget(failed, new EvaluationBudget.Limits(1, 100, 10), meters);
            assertThatThrownBy(() -> budget.embed(List.of("note")))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(budget.usage().attemptedRequests()).isEqualTo(1);
            assertThat(budget.usage().submittedInputBytes()).isEqualTo(4);
            assertThatThrownBy(() -> budget.embed(List.of("note")))
                    .isInstanceOf(EvaluationBudget.LimitReached.class);
            assertThat(calls).hasValue(1);
        } finally {
            meters.close();
        }
    }

    @Test
    void stopsAfterReportedTokensCrossTheCeilingWithoutRetrying() {
        var meters = new SimpleMeterRegistry();
        try {
            var calls = new AtomicInteger();
            var budget =
                    new EvaluationBudget(
                            provider(meters, calls, 11, true),
                            new EvaluationBudget.Limits(3, 100, 10),
                            meters);
            assertThatThrownBy(() -> budget.embed(List.of("note")))
                    .isInstanceOf(EvaluationBudget.LimitReached.class);
            assertThatThrownBy(() -> budget.embed(List.of("note")))
                    .isInstanceOf(EvaluationBudget.LimitReached.class);
            assertThat(calls).hasValue(1);
            assertThat(budget.usage()).isEqualTo(new EvaluationBudget.Usage(1, 4, 1, 1, 11));
        } finally {
            meters.close();
        }
    }

    @Test
    void distinguishesMissingUsageFromAReportedZeroTokenCount() throws Exception {
        var meters = new SimpleMeterRegistry();
        try {
            var calls = new AtomicInteger();
            var budget =
                    new EvaluationBudget(
                            provider(meters, calls, 0, false),
                            new EvaluationBudget.Limits(1, 100, 10),
                            meters);
            budget.embed(List.of("note"));
            assertThat(budget.usage()).isEqualTo(new EvaluationBudget.Usage(1, 4, 1, 0, 0));
            assertThatThrownBy(() -> budget.embed(List.of("other")))
                    .isInstanceOf(EvaluationBudget.LimitReached.class);
        } finally {
            meters.close();
        }
    }

    static EmbeddingProvider provider(
            MeterRegistry meters, AtomicInteger calls, int tokens, boolean reportsUsage) {
        return new EmbeddingProvider() {
            public EmbeddingModel model() {
                return MODEL;
            }

            public List<EmbeddingVector> embed(List<String> inputs) {
                calls.incrementAndGet();
                meters.counter("secondbrain.embedding.requests", "outcome", "success").increment();
                if (reportsUsage) {
                    meters.counter("secondbrain.embedding.usage.reports").increment();
                    meters.counter("secondbrain.embedding.tokens").increment(tokens);
                }
                return inputs.stream().map(input -> vector()).toList();
            }
        };
    }

    static EmbeddingVector vector() {
        var values = new float[1024];
        values[0] = 1;
        return new EmbeddingVector(MODEL, values);
    }
}
