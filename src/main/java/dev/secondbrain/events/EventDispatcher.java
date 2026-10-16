package dev.secondbrain.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class EventDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(EventDispatcher.class);
    private final JdbcEventStore store;
    private final EventRegistry registry;

    EventDispatcher(JdbcEventStore store, EventRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    void dispatch(DeliveryLease lease) {
        var registered = registry.handler(lease.handlerId());
        if (registered.isEmpty()) {
            store.fail(lease, DeliveryFailure.Code.HANDLER_MISSING, false);
            return;
        }
        var handler = registered.get();
        if (!handler.eventType().name().equals(lease.event().type().name())) {
            store.fail(lease, DeliveryFailure.Code.EVENT_UNSUPPORTED, false);
            return;
        }
        if (!handler.eventType().equals(lease.event().type())) {
            store.fail(lease, DeliveryFailure.Code.SCHEMA_UNSUPPORTED, false);
            return;
        }
        try {
            var effect = handler.prepare(lease.event());
            boolean committed = store.complete(lease, effect);
            LOG.info(
                    "event_delivery outcome={} event_id={} delivery_id={} attempt={}",
                    committed ? "acknowledged" : "lease_lost",
                    lease.event().eventId(),
                    lease.deliveryId(),
                    lease.attempt());
        } catch (DeliveryFailure permanent) {
            store.fail(lease, DeliveryFailure.Code.HANDLER_PERMANENT, false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            store.fail(lease, DeliveryFailure.Code.TIMED_OUT, true);
        } catch (Exception transientFailure) {
            // Never persist/log exception messages: provider exceptions can contain private input.
            store.fail(lease, DeliveryFailure.Code.HANDLER_TRANSIENT, true);
        }
    }
}
