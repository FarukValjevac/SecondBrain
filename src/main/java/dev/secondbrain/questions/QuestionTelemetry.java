package dev.secondbrain.questions;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Receives only closed outcomes and validated counts, never questions, replies or exceptions. */
public final class QuestionTelemetry {
    private final Optional<MeterRegistry> meters;
    private final ObservationRegistry observations;

    public QuestionTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = Optional.of(Objects.requireNonNull(meters, "meters"));
        this.observations = Objects.requireNonNull(observations, "observations");
        for (var kind : Kind.values())
            for (var outcome : Outcome.values())
                safely(() -> meters.counter(kind.counter, "outcome", outcome.tag()));
        for (var kind : TokenKind.values()) {
            safely(() -> meters.counter("secondbrain.chat.tokens", "kind", kind.tag()));
            safely(() -> meters.counter("secondbrain.chat.usage.reports", "kind", kind.tag()));
        }
    }

    private QuestionTelemetry() {
        meters = Optional.empty();
        observations = ObservationRegistry.NOOP;
    }

    public static QuestionTelemetry disabled() {
        return new QuestionTelemetry();
    }

    Operation startQuestion() {
        return start(Kind.QUESTION);
    }

    Operation startProvider() {
        return start(Kind.CHAT);
    }

    Operation startAnswer() {
        return start(Kind.ANSWER);
    }

    private Operation start(Kind kind) {
        var observation = Observation.NOOP;
        try {
            observation =
                    Observation.createNotStarted(kind.timer, observations)
                            .contextualName(kind.span);
            // Inherit the caller's trace without replacing its scope or capturing request data.
            observation.start();
        } catch (RuntimeException unavailable) {
            safely(observation::stop);
            observation = Observation.NOOP;
            warn();
        }
        return new Operation(kind, observation);
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException unavailable) {
            warn();
        }
    }

    private static void warn() {
        try {
            LoggerFactory.getLogger(QuestionTelemetry.class)
                    .atWarn()
                    .addKeyValue("code", "TELEMETRY_FAILED")
                    .log("Question telemetry unavailable");
        } catch (RuntimeException loggingUnavailable) {
            /* Diagnostics cannot change the question/provider result. */
        }
    }

    private enum Kind {
        QUESTION(
                "secondbrain.question.requests",
                "secondbrain.question.duration",
                "question.generate"),
        CHAT("secondbrain.chat.requests", "secondbrain.chat.request.duration", "chat.request"),
        ANSWER(
                "secondbrain.question.answers",
                "secondbrain.question.answer.duration",
                "question.answer");

        private final String counter;
        private final String timer;
        private final String span;

        Kind(String counter, String timer, String span) {
            this.counter = counter;
            this.timer = timer;
            this.span = span;
        }
    }

    enum TokenKind {
        INPUT,
        OUTPUT,
        TOTAL;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum Outcome {
        CANDIDATE_ANSWER,
        ANSWERED,
        INSUFFICIENT_INFORMATION,
        EMPTY_SCOPE,
        NO_EVIDENCE,
        REJECTED,
        NO_COMPATIBLE_INDEX,
        DATABASE_UNAVAILABLE,
        INCOMPATIBLE_MODEL,
        INVALID_EMBEDDING,
        INVALID_EVIDENCE,
        MISSING_CITATIONS,
        INVALID_CITATION,
        DUPLICATE_CITATION,
        SOURCE_UNAVAILABLE,
        ACCESS_DENIED,
        MODEL_UNAVAILABLE,
        INVALID_INPUT,
        INVALID_RESPONSE,
        OUTPUT_LIMIT,
        RATE_LIMITED,
        QUOTA_EXHAUSTED,
        UNAVAILABLE,
        TIMED_OUT,
        INTERRUPTED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    final class Operation implements AutoCloseable {
        private final Kind kind;
        private final Observation observation;
        private Outcome outcome = Outcome.UNAVAILABLE;
        private OptionalLong inputTokens = OptionalLong.empty();
        private OptionalLong outputTokens = OptionalLong.empty();
        private OptionalLong totalTokens = OptionalLong.empty();
        private boolean closed;

        private Operation(Kind kind, Observation observation) {
            this.kind = kind;
            this.observation = observation;
        }

        void candidate(ChatProvider.Outcome outcome) {
            this.outcome =
                    switch (outcome) {
                        case ANSWERED -> Outcome.CANDIDATE_ANSWER;
                        case INSUFFICIENT_INFORMATION -> Outcome.INSUFFICIENT_INFORMATION;
                    };
        }

        void failure(QuestionFailure.Reason reason) {
            outcome = Outcome.valueOf(reason.name());
        }

        void failure(ChatFailure.Reason reason) {
            outcome = Outcome.valueOf(reason.name());
        }

        void outcome(Outcome outcome) {
            this.outcome = Objects.requireNonNull(outcome, "outcome");
        }

        void usage(OptionalLong input, OptionalLong output, OptionalLong total) {
            inputTokens = input;
            outputTokens = output;
            totalTokens = total;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (meters.isEmpty()) return;
            var registry = meters.orElseThrow();
            safely(() -> registry.counter(kind.counter, "outcome", outcome.tag()).increment());
            if (kind == Kind.CHAT
                    && (outcome == Outcome.CANDIDATE_ANSWER
                            || outcome == Outcome.INSUFFICIENT_INFORMATION)) {
                recordTokens(registry, TokenKind.INPUT, inputTokens);
                recordTokens(registry, TokenKind.OUTPUT, outputTokens);
                recordTokens(registry, TokenKind.TOTAL, totalTokens);
            }
            safely(() -> observation.lowCardinalityKeyValue("outcome", outcome.tag()));
            safely(observation::stop);
            safely(
                    () ->
                            LoggerFactory.getLogger(QuestionTelemetry.class)
                                    .atInfo()
                                    .addKeyValue("operation", kind.span)
                                    .addKeyValue("outcome", outcome.tag())
                                    .log("Question operation finished"));
        }

        private void recordTokens(MeterRegistry registry, TokenKind kind, OptionalLong tokens) {
            if (tokens.isEmpty() || tokens.getAsLong() < 0 || tokens.getAsLong() > 1_000_000)
                return;
            safely(
                    () ->
                            registry.counter("secondbrain.chat.tokens", "kind", kind.tag())
                                    .increment(tokens.getAsLong()));
            safely(
                    () ->
                            registry.counter("secondbrain.chat.usage.reports", "kind", kind.tag())
                                    .increment());
        }
    }
}
