package dev.secondbrain.events;

import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/** Durable propagation is limited to a version-00 traceparent; baggage is never persisted. */
final class EventTracing {
    enum Outcome {
        ACKNOWLEDGED,
        FAILURE_RECORDED,
        LEASE_LOST,
        INFRASTRUCTURE_FAILURE
    }

    private final Tracer tracer;

    EventTracing(Tracer tracer) {
        this.tracer = tracer;
    }

    EventEnvelope capture(EventEnvelope event) {
        try {
            var current = tracer.currentSpan();
            if (current == null
                    || current.context().traceId() == null
                    || current.context().traceId().length() != 32
                    || current.context().traceId().equals("0".repeat(32))) {
                return event;
            }
            var context = current.context();
            String parent =
                    "00-"
                            + context.traceId()
                            + "-"
                            + context.spanId()
                            + "-"
                            + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
            return new EventEnvelope(
                    event.eventId(),
                    event.type(),
                    event.documentId(),
                    event.processingVersion(),
                    event.occurredAt(),
                    event.correlationId(),
                    event.causationId(),
                    parent,
                    event.references());
        } catch (RuntimeException unavailable) {
            reportUnavailable();
            return event;
        }
    }

    Attempt start(DeliveryLease lease) {
        Span span = Span.NOOP;
        try {
            var event = lease.event();
            var builder =
                    tracer.spanBuilder()
                            .setNoParent()
                            .name("event.delivery")
                            .kind(Span.Kind.CONSUMER);
            if (event.traceParent() != null) {
                String parent = event.traceParent();
                var remote =
                        tracer.traceContextBuilder()
                                .traceId(parent.substring(3, 35))
                                .spanId(parent.substring(36, 52))
                                .sampled(parent.endsWith("01"))
                                .build();
                builder.addLink(new Link(remote));
            }
            span = builder.start();
            span.tag("correlation_id", event.correlationId().toString())
                    .tag("event_id", event.eventId().toString())
                    .tag("delivery_id", lease.deliveryId().toString())
                    .tag("document_id", event.documentId().toString())
                    .tag("attempt", lease.attempt())
                    .tag("processing_version", event.processingVersion());
            if (event.causationId() != null) {
                span.tag("causation_id", event.causationId().toString());
            }
            return new Attempt(span, tracer.withSpan(span));
        } catch (RuntimeException unavailable) {
            reportUnavailable();
            try {
                span.end();
            } catch (RuntimeException ignored) {
            }
            return new Attempt(Span.NOOP, () -> {});
        }
    }

    private static void reportUnavailable() {
        org.slf4j.LoggerFactory.getLogger(EventTracing.class)
                .warn("Trace instrumentation unavailable code=TELEMETRY_FAILED");
    }

    static final class Attempt implements AutoCloseable {
        private final Span span;
        private final Tracer.SpanInScope scope;

        Attempt(Span span, Tracer.SpanInScope scope) {
            this.span = span;
            this.scope = scope;
        }

        void outcome(Outcome outcome) {
            try {
                span.tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT));
            } catch (RuntimeException unavailable) {
                reportUnavailable();
            }
        }

        @Override
        public void close() {
            try {
                scope.close();
            } catch (RuntimeException unavailable) {
                reportUnavailable();
            }
            try {
                span.end();
            } catch (RuntimeException unavailable) {
                reportUnavailable();
            }
        }
    }
}
