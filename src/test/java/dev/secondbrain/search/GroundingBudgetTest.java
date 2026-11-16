package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.secondbrain.questions.ChatProvider;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class GroundingBudgetTest {
    private final ChatProvider.Request request =
            new ChatProvider.Request(
                    "Question?", List.of(new ChatProvider.Passage(UUID.randomUUID(), "Evidence.")));

    @Test
    void reservesRequestsAndOutputBeforeIoAndLeavesUnknownTokensNull() throws Exception {
        var calls = new AtomicInteger();
        var meters = new SimpleMeterRegistry();
        try {
            var budget =
                    new GroundingBudget(
                            r -> {
                                calls.incrementAndGet();
                                return new ChatProvider.Reply(
                                        ChatProvider.Outcome.INSUFFICIENT_INFORMATION,
                                        "",
                                        List.of());
                            },
                            new GroundingBudget.Limits(2, 100, 10, 10, 100),
                            meters);
            budget.generate(request);
            assertThatThrownBy(() -> budget.generate(request))
                    .isInstanceOf(EvaluationBudget.LimitReached.class);
            assertThat(calls).hasValue(1);
            assertThat(budget.usage().reservedOutputTokens()).isEqualTo(10);
            assertThat(budget.usage().reportedTotalTokens()).isNull();
            assertThat(budget.usage().totalUsageReports()).isZero();
        } finally {
            meters.close();
        }
    }

    @Test
    void failuresConsumeBudgetAndAreNeverRetriedAutomatically() {
        var meters = new SimpleMeterRegistry();
        try {
            var calls = new AtomicInteger();
            var budget =
                    new GroundingBudget(
                            r -> {
                                calls.incrementAndGet();
                                throw new IllegalStateException("private sentinel");
                            },
                            new GroundingBudget.Limits(1, 100, 10, 10, 100),
                            meters);
            assertThatThrownBy(() -> budget.generate(request))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> budget.generate(request))
                    .isInstanceOf(EvaluationBudget.LimitReached.class);
            assertThat(calls).hasValue(1);
        } finally {
            meters.close();
        }
    }

    @Test
    void countsUtf8BytesAndStopsBeforeOversizedInput() {
        var meters = new SimpleMeterRegistry();
        try {
            var budget =
                    new GroundingBudget(
                            r -> {
                                throw new AssertionError("must not call");
                            },
                            new GroundingBudget.Limits(1, 1, 10, 10, 100),
                            meters);
            assertThatThrownBy(() -> budget.generate(request))
                    .isInstanceOf(EvaluationBudget.LimitReached.class);
            assertThat(budget.usage().attemptedRequests()).isZero();
        } finally {
            meters.close();
        }
    }

    @Test
    void reportedCeilingStopsAfterOneResponseAndPreservesActualCapture() {
        var meters = new SimpleMeterRegistry();
        try {
            var budget =
                    new GroundingBudget(
                            r -> {
                                meters.counter("secondbrain.chat.tokens", "kind", "total")
                                        .increment(11);
                                meters.counter("secondbrain.chat.usage.reports", "kind", "total")
                                        .increment();
                                return new ChatProvider.Reply(
                                        ChatProvider.Outcome.INSUFFICIENT_INFORMATION,
                                        "",
                                        List.of());
                            },
                            new GroundingBudget.Limits(2, 100, 10, 20, 10),
                            meters);
            assertThatThrownBy(() -> budget.generate(request))
                    .isInstanceOf(EvaluationBudget.LimitReached.class);
            assertThat(budget.lastRequest()).isEqualTo(request);
            assertThat(budget.lastReply()).isNotNull();
            assertThat(budget.usage().reportedTotalTokens()).isEqualTo(11);
        } finally {
            meters.close();
        }
    }

    @Test
    void releaseConsentRequiresFreeConfirmationAuthorizationAndExplicitBudgets() {
        var p = new Properties();
        assertThatThrownBy(() -> ReleaseEvaluationSettings.from(p))
                .isInstanceOf(IllegalArgumentException.class);
        p.setProperty("evaluation.allowLive", "true");
        p.setProperty("evaluation.maxRequests", "22");
        p.setProperty("evaluation.maxInputBytes", "8192");
        p.setProperty("evaluation.maxReportedTokens", "8192");
        assertThatThrownBy(() -> ReleaseEvaluationSettings.from(p))
                .hasMessageContaining("Workers Free");
        p.setProperty("evaluation.workersFreeConfirmed", "true");
        p.setProperty("evaluation.authorization", "local-owner-2026-11-12");
        p.setProperty("evaluation.eligibilityCheckedOn", "2026-11-12");
        p.setProperty("evaluation.chatRequests", "20");
        p.setProperty("evaluation.chatTextBytes", "160000");
        p.setProperty("evaluation.outputTokens", "512");
        p.setProperty("evaluation.reservedOutputTokens", "10240");
        p.setProperty("evaluation.chatReportedTokens", "100000");
        assertThat(ReleaseEvaluationSettings.from(p).authorizationReference())
                .isEqualTo("local-owner-2026-11-12");
        p.setProperty("evaluation.authorization", "Bearer private-token");
        assertThatThrownBy(() -> ReleaseEvaluationSettings.from(p))
                .hasMessageContaining("safe authorization");
    }
}
