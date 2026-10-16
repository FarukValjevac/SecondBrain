package dev.secondbrain.events;

import java.time.Clock;
import java.time.Instant;
import java.util.function.DoubleSupplier;

final class EventRetryPolicy {
    private final EventDeliveryProperties settings;
    private final Clock clock;
    private final DoubleSupplier jitter;

    EventRetryPolicy(EventDeliveryProperties settings, Clock clock, DoubleSupplier jitter) {
        this.settings = settings;
        this.clock = clock;
        this.jitter = jitter;
    }

    Instant nextAttemptAt(int attempt) {
        if (attempt < 1 || attempt > 10) {
            throw new IllegalArgumentException("Invalid attempt");
        }
        long ceiling =
                Math.min(
                        settings.maxBackoff().toMillis(),
                        settings.initialBackoff().toMillis() * (1L << (attempt - 1)));
        double sample = jitter.getAsDouble();
        if (sample < 0 || sample > 1 || !Double.isFinite(sample)) {
            throw new IllegalArgumentException("Jitter must be between zero and one");
        }
        return clock.instant().plusMillis((long) (ceiling * (0.5 + 0.5 * sample)));
    }
}
