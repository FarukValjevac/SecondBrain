package dev.secondbrain.events;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/** Same-delivery retry; the owning feature supplies its locked visibility/version guard. */
public final class EventRetryService {
    private final JdbcEventStore store;
    private final Supplier<EventRegistry> registry;

    EventRetryService(JdbcEventStore store, Supplier<EventRegistry> registry) {
        this.store = Objects.requireNonNull(store);
        this.registry = Objects.requireNonNull(registry);
    }

    /**
     * The guard runs inside the short retry transaction, after locking the delivery. It must lock
     * and validate the owning domain row and perform database-only work. The expected cycle makes
     * duplicate or stale form submissions harmless, even after the new cycle has already failed.
     */
    public boolean retry(
            UUID deliveryId,
            int expectedCycle,
            UUID correlationId,
            BiPredicate<String, EventEnvelope> guard) {
        Objects.requireNonNull(deliveryId);
        Objects.requireNonNull(correlationId);
        Objects.requireNonNull(guard);
        if (expectedCycle < 0) throw new IllegalArgumentException("Invalid retry cycle");
        return store.retryFailed(
                deliveryId,
                expectedCycle,
                correlationId,
                (handler, event) ->
                        registry.get()
                                        .handler(handler)
                                        .filter(
                                                registered ->
                                                        registered.eventType().equals(event.type()))
                                        .isPresent()
                                && guard.test(handler, event));
    }
}
