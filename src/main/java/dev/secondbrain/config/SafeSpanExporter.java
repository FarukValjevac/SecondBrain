package dev.secondbrain.config;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Export boundary: IDs and fixed outcomes only, without URLs, baggage, exception events, or
 * resources.
 */
final class SafeSpanExporter implements SpanExporter {
    private static final Set<String> IDS =
            Set.of("correlation_id", "event_id", "delivery_id", "document_id", "causation_id");
    private static final Set<String> OUTCOMES =
            Set.of(
                    "acknowledged",
                    "failure_recorded",
                    "lease_lost",
                    "infrastructure_failure",
                    "accepted",
                    "rejected",
                    "unavailable",
                    "completed",
                    "failed",
                    "requested",
                    "unchanged");
    private static final Set<String> NAMES =
            Set.of("event.delivery", "document.upload", "document.cleanup", "document.deletion");
    private final SpanExporter delegate;
    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean();

    SafeSpanExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        try {
            return delegate.export(spans.stream().map(SafeSpanExporter::sanitize).toList());
        } catch (RuntimeException unavailable) {
            return CompletableResultCode.ofFailure();
        }
    }

    static SpanData sanitize(SpanData source) {
        var fields = Attributes.builder();
        source.getAttributes()
                .forEach(
                        (key, value) -> {
                            String name = key.getKey();
                            String text = value.toString();
                            if ((IDS.contains(name)
                                            && text.matches(
                                                    "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
                                    || (name.equals("outcome") && OUTCOMES.contains(text))
                                    || ((name.equals("attempt")
                                                    || name.equals("processing_version"))
                                            && text.matches("[1-9][0-9]{0,18}"))) {
                                fields.put(name, text);
                            }
                        });
        var safe = fields.build();
        return new DelegatingSpanData(source) {
            @Override
            public String getName() {
                return NAMES.contains(source.getName())
                        ? source.getName()
                        : "application.operation";
            }

            @Override
            public Attributes getAttributes() {
                return safe;
            }

            @Override
            public int getTotalAttributeCount() {
                return safe.size();
            }

            @Override
            public List<EventData> getEvents() {
                return List.of();
            }

            @Override
            public int getTotalRecordedEvents() {
                return 0;
            }

            @Override
            public StatusData getStatus() {
                return StatusData.create(source.getStatus().getStatusCode(), "");
            }

            @Override
            public SpanContext getSpanContext() {
                return clean(source.getSpanContext());
            }

            @Override
            public SpanContext getParentSpanContext() {
                return clean(source.getParentSpanContext());
            }

            @Override
            public List<LinkData> getLinks() {
                return source.getLinks().stream()
                        .limit(1)
                        .map(link -> LinkData.create(clean(link.getSpanContext())))
                        .toList();
            }

            @Override
            public int getTotalRecordedLinks() {
                return getLinks().size();
            }

            @Override
            public Resource getResource() {
                return Resource.create(
                        Attributes.builder().put("service.name", "secondbrain").build());
            }

            @Override
            public InstrumentationScopeInfo getInstrumentationScopeInfo() {
                return InstrumentationScopeInfo.create("secondbrain");
            }

            @Override
            public String toString() {
                return "SafeSpanData[" + getName() + "]";
            }
        };
    }

    private static SpanContext clean(SpanContext context) {
        return SpanContext.create(
                context.getTraceId(),
                context.getSpanId(),
                context.getTraceFlags(),
                TraceState.getDefault());
    }

    @Override
    public CompletableResultCode flush() {
        try {
            return delegate.flush();
        } catch (RuntimeException unavailable) {
            return CompletableResultCode.ofFailure();
        }
    }

    @Override
    public CompletableResultCode shutdown() {
        // Both the SDK and Spring own shutdown hooks; close the transport exactly once.
        if (!closed.compareAndSet(false, true)) {
            return CompletableResultCode.ofSuccess();
        }
        try {
            return delegate.shutdown();
        } catch (RuntimeException unavailable) {
            return CompletableResultCode.ofFailure();
        }
    }
}
