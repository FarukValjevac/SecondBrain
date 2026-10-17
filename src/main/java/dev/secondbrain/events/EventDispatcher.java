package dev.secondbrain.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class EventDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(EventDispatcher.class);
    private final JdbcEventStore store;
    private final EventRegistry registry;
    private final EventTracing tracing;

    EventDispatcher(JdbcEventStore store, EventRegistry registry, EventTracing tracing) {
        this.store = store;
        this.registry = registry;
        this.tracing = tracing;
    }

    void dispatch(DeliveryLease lease) {
        var attempt = tracing.start(lease);
        try (attempt) {
            try {
                dispatch(lease, attempt);
            } catch (RuntimeException unavailable) {
                attempt.outcome(EventTracing.Outcome.INFRASTRUCTURE_FAILURE);
                throw unavailable;
            }
        }
    }

    private void dispatch(DeliveryLease lease, EventTracing.Attempt attempt) {
        var registered = registry.handler(lease.handlerId());
        if (registered.isEmpty()) {
            fail(lease, DeliveryFailure.Code.HANDLER_MISSING, false, attempt);
            return;
        }
        var handler = registered.get();
        if (!handler.eventType().name().equals(lease.event().type().name())) {
            fail(lease, DeliveryFailure.Code.EVENT_UNSUPPORTED, false, attempt);
            return;
        }
        if (!handler.eventType().equals(lease.event().type())) {
            fail(lease, DeliveryFailure.Code.SCHEMA_UNSUPPORTED, false, attempt);
            return;
        }
        try {
            var effect = handler.prepare(lease.event());
            boolean committed = store.complete(lease, effect);
            attempt.outcome(
                    committed
                            ? EventTracing.Outcome.ACKNOWLEDGED
                            : EventTracing.Outcome.LEASE_LOST);
            LOG.atInfo()
                    .addKeyValue("operation", "event_delivery")
                    .addKeyValue("outcome", committed ? "acknowledged" : "lease_lost")
                    .log("Event delivery finished");
        } catch (DeliveryFailure permanent) {
            var code =
                    permanent.retryable()
                            ? DeliveryFailure.Code.HANDLER_TRANSIENT
                            : DeliveryFailure.Code.HANDLER_PERMANENT;
            boolean recorded =
                    permanent.effect().isPresent()
                            ? store.fail(
                                    lease,
                                    code,
                                    permanent.retryable(),
                                    permanent.effect().orElseThrow())
                            : store.fail(lease, code, permanent.retryable());
            reportFailure(recorded, code, attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(lease, DeliveryFailure.Code.TIMED_OUT, true, attempt);
        } catch (Exception transientFailure) {
            // Never persist/log exception messages: provider exceptions can contain private input.
            fail(lease, DeliveryFailure.Code.HANDLER_TRANSIENT, true, attempt);
        }
    }

    private void fail(
            DeliveryLease lease,
            DeliveryFailure.Code code,
            boolean retryable,
            EventTracing.Attempt attempt) {
        boolean recorded = store.fail(lease, code, retryable);
        reportFailure(recorded, code, attempt);
    }

    private void reportFailure(
            boolean recorded, DeliveryFailure.Code code, EventTracing.Attempt attempt) {
        attempt.outcome(
                recorded ? EventTracing.Outcome.FAILURE_RECORDED : EventTracing.Outcome.LEASE_LOST);
        LOG.atWarn()
                .addKeyValue("operation", "event_delivery")
                .addKeyValue("outcome", recorded ? "failure_recorded" : "lease_lost")
                .addKeyValue("code", code.name())
                .log("Event delivery failed");
    }
}
