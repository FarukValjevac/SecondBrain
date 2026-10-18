package dev.secondbrain.events;

import java.util.UUID;
import java.util.function.Supplier;

public final class EventPublisher {
    private final JdbcEventStore store;
    private final Supplier<EventRegistry> registry;
    private final EventTracing tracing;

    EventPublisher(JdbcEventStore store, Supplier<EventRegistry> registry, EventTracing tracing) {
        this.store = store;
        this.registry = registry;
        this.tracing = tracing;
    }

    /**
     * Joins an existing JDBC transaction; never commits independently of the caller's domain
     * writes. Repeated logical publications return the original event ID, including after payload
     * cleanup. The configured PostgreSQL timeouts apply to the rest of this transaction as well.
     */
    public UUID publish(EventEnvelope event) {
        return store.publish(tracing.capture(event), registry.get().recipients(event.type()));
    }

    /** Bounded reconciliation for a newly registered consumer; repeated calls are idempotent. */
    public int scheduleMissing(EventEnvelope.Type type, String handler, int limit) {
        if (!registry.get().recipients(type).contains(handler))
            throw new IllegalArgumentException("Handler is not registered for this event");
        return store.scheduleMissing(type, handler, limit);
    }

    /**
     * Joins deletion's fenced transaction. Discards other document payloads/deliveries while
     * retaining keys and the current cleanup event. Version sequences may belong to different
     * processing stages, so their numeric values cannot determine deletion eligibility. Call before
     * locking/removing the document row.
     */
    public int discardOtherDocumentEvents(UUID documentId, UUID retainedEventId) {
        return store.discardOtherDocumentEvents(documentId, retainedEventId);
    }
}
