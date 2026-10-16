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
            fail(lease, DeliveryFailure.Code.HANDLER_MISSING, false);
            return;
        }
        var handler = registered.get();
        if (!handler.eventType().name().equals(lease.event().type().name())) {
            fail(lease, DeliveryFailure.Code.EVENT_UNSUPPORTED, false);
            return;
        }
        if (!handler.eventType().equals(lease.event().type())) {
            fail(lease, DeliveryFailure.Code.SCHEMA_UNSUPPORTED, false);
            return;
        }
        try {
            var effect = handler.prepare(lease.event());
            boolean committed = store.complete(lease, effect);
            LOG.atInfo()
                    .addKeyValue("operation", "event_delivery")
                    .addKeyValue("outcome", committed ? "acknowledged" : "lease_lost")
                    .log("Event delivery finished");
        } catch (DeliveryFailure permanent) {
            fail(lease, DeliveryFailure.Code.HANDLER_PERMANENT, false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(lease, DeliveryFailure.Code.TIMED_OUT, true);
        } catch (Exception transientFailure) {
            // Never persist/log exception messages: provider exceptions can contain private input.
            fail(lease, DeliveryFailure.Code.HANDLER_TRANSIENT, true);
        }
    }

    private void fail(DeliveryLease lease, DeliveryFailure.Code code, boolean retryable) {
        boolean recorded = store.fail(lease, code, retryable);
        LOG.atWarn()
                .addKeyValue("operation", "event_delivery")
                .addKeyValue("outcome", recorded ? "failure_recorded" : "lease_lost")
                .addKeyValue("code", code.name())
                .log("Event delivery failed");
    }
}
