package dev.secondbrain.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.util.function.ToDoubleFunction;

/** One bounded database read per five seconds across all backlog gauges. */
final class EventBacklogMetrics {
    private final JdbcEventStore store;
    private final Clock clock;
    private Instant nextRead = Instant.MIN;
    private JdbcEventStore.Backlog cached;

    EventBacklogMetrics(
            JdbcEventStore store,
            MeterRegistry registry,
            Clock clock,
            ObjectProvider<DurableEventWorker> worker) {
        this.store = store;
        this.clock = clock;
        register(registry, "ready", JdbcEventStore.Backlog::ready);
        register(registry, "delayed", JdbcEventStore.Backlog::delayed);
        register(registry, "retrying", JdbcEventStore.Backlog::retrying);
        register(registry, "running", JdbcEventStore.Backlog::running);
        register(registry, "failed", JdbcEventStore.Backlog::failed);
        Gauge.builder(
                        "secondbrain.event.backlog.oldest",
                        this,
                        value -> value.value(JdbcEventStore.Backlog::age))
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(
                        "secondbrain.event.backlog.available",
                        this,
                        value -> value.snapshot() == null ? 0 : 1)
                .register(registry);
        Gauge.builder(
                        "secondbrain.event.workers.active",
                        worker,
                        provider -> {
                            var current = provider.getIfAvailable();
                            return current == null ? 0 : current.activeCount();
                        })
                .strongReference(true)
                .register(registry);
    }

    private void register(
            MeterRegistry registry, String state, ToDoubleFunction<JdbcEventStore.Backlog> read) {
        Gauge.builder("secondbrain.event.backlog", this, value -> value.value(read))
                .tag("state", state)
                .register(registry);
    }

    private double value(ToDoubleFunction<JdbcEventStore.Backlog> read) {
        var current = snapshot();
        return current == null ? Double.NaN : read.applyAsDouble(current);
    }

    private synchronized JdbcEventStore.Backlog snapshot() {
        if (!clock.instant().isBefore(nextRead)) {
            try {
                cached = store.backlog();
            } catch (RuntimeException unavailable) {
                cached = null;
            } finally {
                // Cache failures too, so a slow timeout is not repeated for every meter in a
                // scrape.
                nextRead = clock.instant().plusSeconds(5);
            }
        }
        return cached;
    }
}
