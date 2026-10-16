package dev.secondbrain.events;

import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

class EventTracingTest {
    @Test
    void capturesProducerAndLinksIndependentRetrySpansAfterRebuildingTheTracer() throws Exception {
        var exporter = InMemorySpanExporter.create();
        try (var provider =
                SdkTracerProvider.builder()
                        .setSampler(Sampler.alwaysOn())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build()) {
            var listener = new Slf4JEventListener();
            var tracer =
                    new OtelTracer(
                            provider.get("test"), new OtelCurrentTraceContext(), listener::onEvent);
            var original =
                    new EventEnvelope(
                            UUID.randomUUID(),
                            new EventEnvelope.Type("Probe", 1),
                            UUID.randomUUID(),
                            1,
                            Instant.EPOCH,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            null,
                            Map.of());
            var producer = tracer.nextSpan().name("producer").start();
            EventEnvelope persisted;
            var scope = tracer.withSpan(producer);
            try (scope) {
                persisted = new EventTracing(tracer).capture(original);
            }
            producer.end();
            Assertions.assertThat(persisted.traceParent()).contains(producer.context().traceId());
            // A new adapter has no producer scope: the only link source is the stored envelope.
            var consumer =
                    new EventTracing(
                            new OtelTracer(
                                    provider.get("restarted"),
                                    new OtelCurrentTraceContext(),
                                    listener::onEvent));
            try (var thread = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                thread.submit(
                                () -> {
                                    for (int attempt = 1; attempt <= 2; attempt++) {
                                        var lease =
                                                new DeliveryLease(
                                                        UUID.randomUUID(),
                                                        "probe",
                                                        UUID.randomUUID(),
                                                        UUID.randomUUID(),
                                                        attempt,
                                                        Instant.MAX,
                                                        persisted);
                                        var context = new DeliveryLogContext(lease);
                                        try (context) {
                                            var span = consumer.start(lease);
                                            try (span) {
                                                Assertions.assertThat(MDC.get("traceId"))
                                                        .isNotBlank();
                                                span.outcome(
                                                        attempt == 1
                                                                ? EventTracing.Outcome
                                                                        .FAILURE_RECORDED
                                                                : EventTracing.Outcome
                                                                        .ACKNOWLEDGED);
                                            }
                                        }
                                        Assertions.assertThat(MDC.getCopyOfContextMap())
                                                .isNullOrEmpty();
                                    }
                                })
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            var attempts =
                    exporter.getFinishedSpanItems().stream()
                            .filter(span -> span.getName().equals("event.delivery"))
                            .toList();
            Assertions.assertThat(attempts).hasSize(2);
            Assertions.assertThat(attempts.getFirst().getTraceId())
                    .isNotEqualTo(attempts.getLast().getTraceId());
            for (var span : attempts) {
                Assertions.assertThat(span.getLinks()).hasSize(1);
                Assertions.assertThat(span.getLinks().getFirst().getSpanContext().getTraceId())
                        .isEqualTo(producer.context().traceId());
                Assertions.assertThat(span.getLinks().getFirst().getSpanContext().getSpanId())
                        .isEqualTo(producer.context().spanId());
                Assertions.assertThat(span.getParentSpanContext().isValid()).isFalse();
                Assertions.assertThat(
                                span.getAttributes().get(AttributeKey.stringKey("correlation_id")))
                        .isEqualTo(persisted.correlationId().toString());
                Assertions.assertThat(span.getEvents()).isEmpty();
            }
        }
    }
}
