package dev.secondbrain.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

@ExtendWith(OutputCaptureExtension.class)
class EmbeddingTelemetryTest {
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final ObservationRegistry observations = ObservationRegistry.create();

    @AfterEach
    void closeMeters() {
        meters.close();
    }

    private EmbeddingTelemetry telemetry() {
        observations
                .observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters));
        return new EmbeddingTelemetry(meters, observations);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 37})
    void recordsDurationAndOnlyReportedTokensWithoutDoubleCounting(long tokens) {
        var telemetry = telemetry();
        var operation = telemetry.start();
        clock.add(Duration.ofMillis(125));
        operation.success(tokens < 0 ? OptionalLong.empty() : OptionalLong.of(tokens));
        operation.close();
        operation.close();
        assertThat(
                        meters.get("secondbrain.embedding.requests")
                                .tag("outcome", "success")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(meters.get("secondbrain.embedding.tokens").counter().count())
                .isEqualTo(Math.max(0, tokens));
        assertThat(meters.get("secondbrain.embedding.usage.reports").counter().count())
                .isEqualTo(tokens < 0 ? 0 : 1);
        var timer =
                meters.get("secondbrain.embedding.request.duration")
                        .tag("outcome", "success")
                        .timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(125);
        assertThat(timer.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("outcome", "error");
    }

    @ParameterizedTest
    @EnumSource(EmbeddingFailure.Reason.class)
    void fixedFailuresHaveSeparateCountersAndNeverInventTokenUsage(
            EmbeddingFailure.Reason reason, CapturedOutput output) {
        var telemetry = telemetry();
        try (var operation = telemetry.start()) {
            operation.failure(reason);
        }
        var tag = reason.name().toLowerCase(Locale.ROOT);
        assertThat(
                        meters.get("secondbrain.embedding.requests")
                                .tag("outcome", tag)
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("secondbrain.embedding.request.duration")
                                .tag("outcome", tag)
                                .timer()
                                .count())
                .isEqualTo(1);
        assertThat(meters.get("secondbrain.embedding.usage.reports").counter().count()).isZero();
        assertThat(meters.get("secondbrain.embedding.tokens").counter().count()).isZero();
        assertThat(output)
                .contains("Embedding request finished")
                .doesNotContain("Authorization", "api.cloudflare.com");
    }

    @Test
    void childObservationInheritsTheDeliveryTraceAndLeavesItsScopeUnchanged() {
        var telemetry = telemetry();
        var capture = InMemorySpanExporter.create();
        try (var sdk =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(capture))
                        .build()) {
            var tracer =
                    new OtelTracer(sdk.get("test"), new OtelCurrentTraceContext(), event -> {});
            observations
                    .observationConfig()
                    .observationHandler(new DefaultTracingObservationHandler(tracer));
            var delivery = tracer.nextSpan().name("event.delivery").start();
            var scope = tracer.withSpan(delivery);
            try (scope) {
                try (var operation = telemetry.start()) {
                    assertThat(tracer.currentSpan().context().spanId())
                            .isEqualTo(delivery.context().spanId());
                    assertThat(observations.getCurrentObservation()).isNull();
                    operation.success(OptionalLong.of(17));
                }
                assertThat(tracer.currentSpan().context().spanId())
                        .isEqualTo(delivery.context().spanId());
            } finally {
                delivery.end();
            }
            var child =
                    capture.getFinishedSpanItems().stream()
                            .filter(span -> span.getName().equals("embedding.request"))
                            .findFirst()
                            .orElseThrow();
            assertThat(child.getTraceId()).isEqualTo(delivery.context().traceId());
            assertThat(child.getParentSpanId()).isEqualTo(delivery.context().spanId());
            assertThat(child.getAttributes().get(AttributeKey.stringKey("outcome")))
                    .isEqualTo("success");
            assertThat(child.getEvents()).isEmpty();
            assertThat(tracer.currentSpan()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "stop"})
    void failingMetersOrObservationHandlersCannotReplaceTheBusinessResult(
            String phase, CapturedOutput output) {
        var broken = mock(MeterRegistry.class);
        when(broken.counter(any(String.class), any(String[].class)))
                .thenThrow(new IllegalStateException("private-metric-sentinel"));
        observations
                .observationConfig()
                .observationHandler(
                        new ObservationHandler<Observation.Context>() {
                            @Override
                            public boolean supportsContext(Observation.Context context) {
                                return true;
                            }

                            @Override
                            public void onStart(Observation.Context context) {
                                if (phase.equals("start"))
                                    throw new IllegalStateException("private-handler-sentinel");
                            }

                            @Override
                            public void onStop(Observation.Context context) {
                                if (phase.equals("stop"))
                                    throw new IllegalStateException("private-handler-sentinel");
                            }
                        });
        var telemetry = new EmbeddingTelemetry(broken, observations);
        assertThatCode(
                        () -> {
                            try (var operation = telemetry.start()) {
                                operation.success(OptionalLong.of(19));
                            }
                        })
                .doesNotThrowAnyException();
        var original = new EmbeddingFailure(EmbeddingFailure.Reason.QUOTA_EXHAUSTED, null, null);
        assertThatThrownBy(
                        () -> {
                            try (var operation = telemetry.start()) {
                                operation.failure(original.reason());
                                throw original;
                            }
                        })
                .isSameAs(original);
        assertThat(observations.getCurrentObservation()).isNull();
        assertThat(output).doesNotContain("private-metric-sentinel", "private-handler-sentinel");
    }

    @Test
    void theRealAdapterMeasuresAClosedTransportButDoesNotCountRejectedInput(CapturedOutput output) {
        var telemetry = telemetry();
        var provider =
                new CloudflareEmbeddingProvider(
                        CloudflareEmbeddingProviderTest.settings(),
                        Clock.systemUTC(),
                        URI.create("http://127.0.0.1:9"),
                        telemetry);
        provider.close();
        assertThatThrownBy(() -> provider.embed(List.of())).isInstanceOf(EmbeddingFailure.class);
        assertThat(
                        meters.get("secondbrain.embedding.requests")
                                .tag("outcome", "invalid_input")
                                .counter()
                                .count())
                .isZero();
        assertThatThrownBy(() -> provider.embed(List.of("private-passage-sentinel")))
                .isInstanceOf(EmbeddingFailure.class);
        assertThat(
                        meters.get("secondbrain.embedding.requests")
                                .tag("outcome", "unavailable")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(output)
                .doesNotContain("private-passage-sentinel", "synthetic-test-token", "127.0.0.1:9");
    }

    @Test
    void interruptionHasADistinctOutcomeWithoutChangingTheThreadFlag() {
        var telemetry = telemetry();
        try {
            try (var operation = telemetry.start()) {
                Thread.currentThread().interrupt();
                operation.interrupted();
            }
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(
                            meters.get("secondbrain.embedding.requests")
                                    .tag("outcome", "interrupted")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
        } finally {
            Thread.interrupted();
        }
    }
}
