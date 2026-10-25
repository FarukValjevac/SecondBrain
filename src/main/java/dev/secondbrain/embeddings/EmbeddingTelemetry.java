package dev.secondbrain.embeddings;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Receives only fixed outcomes and validated usage numbers, never input or private exceptions. */
public final class EmbeddingTelemetry {
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingTelemetry.class);
    private final Optional<MeterRegistry> meters;
    private final ObservationRegistry observations;

    public EmbeddingTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = Optional.of(Objects.requireNonNull(meters, "meters"));
        this.observations = Objects.requireNonNull(observations, "observations");
        for (var outcome : Outcome.values()) {
            safely(
                    () ->
                            meters.counter(
                                    "secondbrain.embedding.requests", "outcome", outcome.tag()));
        }
        safely(() -> meters.counter("secondbrain.embedding.tokens"));
        safely(() -> meters.counter("secondbrain.embedding.usage.reports"));
    }

    private EmbeddingTelemetry() {
        meters = Optional.empty();
        observations = ObservationRegistry.NOOP;
    }

    /** For explicit standalone clients or contexts without a telemetry registry. */
    public static EmbeddingTelemetry disabled() {
        return new EmbeddingTelemetry();
    }

    Operation start() {
        var observation = Observation.NOOP;
        try {
            observation =
                    Observation.createNotStarted(
                                    "secondbrain.embedding.request.duration", observations)
                            .contextualName("embedding.request");
            // Inherit the delivery/request trace without changing its scope or sending baggage.
            observation.start();
        } catch (RuntimeException unavailable) {
            safely(observation::stop);
            observation = Observation.NOOP;
            unavailable();
        }
        return new Operation(observation);
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException unavailable) {
            unavailable();
        }
    }

    private static void unavailable() {
        try {
            LOG.atWarn()
                    .addKeyValue("code", "TELEMETRY_FAILED")
                    .log("Embedding telemetry unavailable");
        } catch (RuntimeException unavailable) {
            /* Logging failure must not change inference. */
        }
    }

    enum Outcome {
        SUCCESS,
        ACCESS_DENIED,
        MODEL_UNAVAILABLE,
        INVALID_INPUT,
        INVALID_RESPONSE,
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
        private final Observation observation;
        private Outcome outcome = Outcome.UNAVAILABLE;
        private OptionalLong tokens = OptionalLong.empty();
        private boolean closed;

        private Operation(Observation observation) {
            this.observation = observation;
        }

        void success(OptionalLong tokens) {
            outcome = Outcome.SUCCESS;
            this.tokens = Objects.requireNonNull(tokens, "tokens");
        }

        void failure(EmbeddingFailure.Reason reason) {
            outcome = Outcome.valueOf(reason.name());
        }

        void interrupted() {
            outcome = Outcome.INTERRUPTED;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (meters.isEmpty()) return;
            var registry = meters.orElseThrow();
            safely(
                    () ->
                            registry.counter(
                                            "secondbrain.embedding.requests",
                                            "outcome",
                                            outcome.tag())
                                    .increment());
            if (outcome == Outcome.SUCCESS && tokens.isPresent()) {
                safely(
                        () ->
                                registry.counter("secondbrain.embedding.tokens")
                                        .increment(tokens.getAsLong()));
                safely(() -> registry.counter("secondbrain.embedding.usage.reports").increment());
            }
            safely(() -> observation.lowCardinalityKeyValue("outcome", outcome.tag()));
            safely(observation::stop);
            safely(
                    () ->
                            LOG.atInfo()
                                    .addKeyValue("operation", "embedding_request")
                                    .addKeyValue("outcome", outcome.tag())
                                    .log("Embedding request finished"));
        }
    }
}
