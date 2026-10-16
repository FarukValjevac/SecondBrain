package dev.secondbrain.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;

final class EventMetrics {
    enum Outcome {
        COMPLETED,
        SKIPPED,
        RETRY,
        FAILED
    }

    private final EnumMap<Outcome, Counter> attempts = new EnumMap<>(Outcome.class);
    private final EnumMap<Outcome, Timer> durations = new EnumMap<>(Outcome.class);

    EventMetrics(MeterRegistry registry) {
        for (var outcome : Outcome.values()) {
            String tag = outcome.name().toLowerCase(Locale.ROOT);
            attempts.put(
                    outcome,
                    registry.counter("secondbrain.event.delivery.attempts", "outcome", tag));
            durations.put(
                    outcome,
                    Timer.builder("secondbrain.event.delivery.duration")
                            .tag("outcome", tag)
                            .register(registry));
        }
    }

    void record(Outcome outcome, Duration elapsed) {
        try {
            attempts.get(outcome).increment();
            durations.get(outcome).record(elapsed.isNegative() ? Duration.ZERO : elapsed);
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(EventMetrics.class)
                    .warn("Event metrics unavailable code=TELEMETRY_FAILED");
        }
    }
}
