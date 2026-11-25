package dev.secondbrain.search;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/** Only fixed outcomes and bounded counts enter diagnostics. Telemetry never receives a query. */
final class SearchTelemetry {
    private final Optional<MeterRegistry> meters;
    private final ObservationRegistry observations;

    SearchTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = Optional.of(meters);
        this.observations = observations;
        for (var outcome : Outcome.values())
            safely(() -> meters.counter("secondbrain.search.requests", "outcome", outcome.tag()));
        safely(
                () ->
                        DistributionSummary.builder("secondbrain.search.results")
                                .baseUnit("passages")
                                .description("Final visible passages per completed search")
                                .register(meters));
    }

    private SearchTelemetry() {
        meters = Optional.empty();
        observations = ObservationRegistry.NOOP;
    }

    static SearchTelemetry disabled() {
        return new SearchTelemetry();
    }

    Operation start() {
        var observation = Observation.NOOP;
        try {
            observation =
                    Observation.createNotStarted("secondbrain.search.duration", observations)
                            .contextualName("search.retrieval");
            // Inherit the caller's trace without replacing its thread-local observation scope.
            observation.start();
        } catch (RuntimeException unavailable) {
            safely(observation::stop);
            observation = Observation.NOOP;
            warn();
        }
        return new Operation(observation);
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
            LoggerFactory.getLogger(SearchTelemetry.class)
                    .atWarn()
                    .addKeyValue("code", "TELEMETRY_FAILED")
                    .log("Search telemetry unavailable");
        } catch (RuntimeException loggingUnavailable) {
            /* No domain outcome depends on logging. */
        }
    }

    enum Outcome {
        SUCCESS,
        NO_RESULTS,
        EMPTY_SCOPE,
        NO_COMPATIBLE_INDEX,
        DATABASE_UNAVAILABLE,
        INCOMPATIBLE_MODEL,
        INVALID_EMBEDDING,
        PROVIDER_UNAVAILABLE,
        RATE_LIMITED,
        QUOTA_EXHAUSTED,
        TIMED_OUT,
        INTERRUPTED,
        REJECTED,
        UNAVAILABLE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    final class Operation implements AutoCloseable {
        private final Observation observation;
        private Outcome outcome = Outcome.UNAVAILABLE;
        private OptionalInt results = OptionalInt.empty();
        private boolean closed;

        private Operation(Observation observation) {
            this.observation = observation;
        }

        void success(int count) {
            outcome = count == 0 ? Outcome.NO_RESULTS : Outcome.SUCCESS;
            results = OptionalInt.of(count);
        }

        void emptyScope() {
            outcome = Outcome.EMPTY_SCOPE;
            results = OptionalInt.of(0);
        }

        void failure(SearchFailure.Reason reason) {
            outcome = Outcome.valueOf(reason.name());
            results = OptionalInt.empty();
        }

        void rejected() {
            outcome = Outcome.REJECTED;
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
                                            "secondbrain.search.requests", "outcome", outcome.tag())
                                    .increment());
            if (results.isPresent())
                safely(
                        () ->
                                registry.summary("secondbrain.search.results")
                                        .record(results.getAsInt()));
            safely(() -> observation.lowCardinalityKeyValue("outcome", outcome.tag()));
            safely(observation::stop);
            safely(
                    () -> {
                        var log =
                                LoggerFactory.getLogger(SearchTelemetry.class)
                                        .atInfo()
                                        .addKeyValue("operation", "search_retrieval")
                                        .addKeyValue("outcome", outcome.tag());
                        if (results.isPresent())
                            log.addKeyValue("result_count", results.getAsInt());
                        log.log("Search retrieval finished");
                    });
        }
    }
}
