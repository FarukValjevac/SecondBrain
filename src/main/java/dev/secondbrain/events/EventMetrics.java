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
            safely(
                    () ->
                            attempts.put(
                                    outcome,
                                    registry.counter(
                                            "secondbrain.event.delivery.attempts",
                                            "outcome",
                                            tag)));
            safely(
                    () ->
                            durations.put(
                                    outcome,
                                    Timer.builder("secondbrain.event.delivery.duration")
                                            .tag("outcome", tag)
                                            .register(registry)));
        }
    }

    void record(Outcome outcome, Duration elapsed) {
        // An absent meter failed registration; keep recording the remaining healthy meters.
        var counter = attempts.get(outcome);
        if (counter != null) safely(counter::increment);
        var timer = durations.get(outcome);
        if (timer != null)
            safely(() -> timer.record(elapsed.isNegative() ? Duration.ZERO : elapsed));
    }

    private static void safely(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException unavailable) {
            try {
                LoggerFactory.getLogger(EventMetrics.class)
                        .atWarn()
                        .addKeyValue("code", "TELEMETRY_FAILED")
                        .log("Event metrics unavailable");
            } catch (RuntimeException loggingUnavailable) {
                // Telemetry failure cannot change a committed event delivery's result.
            }
        }
    }
}
