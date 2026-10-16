package dev.secondbrain.events;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * Rebuilds allowlisted log context from a persisted delivery, independent of its producer thread.
 */
final class DeliveryLogContext implements AutoCloseable {
    private final Map<String, String> previous;

    DeliveryLogContext(DeliveryLease lease) {
        var event = lease.event();
        var fields = new HashMap<String, String>();
        fields.put("correlation_id", event.correlationId().toString());
        fields.put("event_id", event.eventId().toString());
        fields.put("delivery_id", lease.deliveryId().toString());
        fields.put("document_id", event.documentId().toString());
        fields.put("processing_version", Long.toString(event.processingVersion()));
        fields.put("attempt", Integer.toString(lease.attempt()));
        if (event.causationId() != null) {
            fields.put("causation_id", event.causationId().toString());
        }
        previous = MDC.getCopyOfContextMap();
        MDC.setContextMap(fields);
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
