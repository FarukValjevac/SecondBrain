package dev.secondbrain.events;

import java.util.UUID;
import java.util.function.Supplier;

public final class EventPublisher {
    private final JdbcEventStore store;
    private final Supplier<EventRegistry> registry;

    EventPublisher(JdbcEventStore store, Supplier<EventRegistry> registry) {
        this.store = store;
        this.registry = registry;
    }

    /**
     * Joins an existing JDBC transaction; never commits independently of the caller's domain
     * writes. Repeated logical publications return the original event ID, including after payload
     * cleanup. The configured PostgreSQL timeouts apply to the rest of this transaction as well.
     */
    public UUID publish(EventEnvelope event) {
        return store.publish(event, registry.get().recipients(event.type()));
    }
}
