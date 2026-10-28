package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.config.MeterFilter;
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

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@ExtendWith(OutputCaptureExtension.class)
class SearchTelemetryTest {
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final ObservationRegistry observations = ObservationRegistry.create();

    @AfterEach
    void closeMeters() {
        meters.close();
    }

    private SearchTelemetry telemetry() {
        observations
                .observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters));
        return new SearchTelemetry(meters, observations);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 20})
    void recordsWholeRequestDurationAndFinalCountsWithoutDoubleCloseOrPrivateLabels(int count) {
        var operation = telemetry().start();
        clock.add(Duration.ofMillis(125));
        operation.success(count);
        operation.close();
        operation.close();
        var outcome = count == 0 ? "no_results" : "success";
        assertThat(
                        meters.get("secondbrain.search.requests")
                                .tag("outcome", outcome)
                                .counter()
                                .count())
                .isEqualTo(1);
        var timer = meters.get("secondbrain.search.duration").tag("outcome", outcome).timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(125);
        var summary = meters.get("secondbrain.search.results").summary();
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.totalAmount()).isEqualTo(count);
        assertThat(summary.getId().getBaseUnit()).isEqualTo("passages");
        assertThat(summary.getId().getTags()).isEmpty();
        for (var meter : meters.getMeters())
            assertThat(meter.getId().getTags())
                    .allSatisfy(tag -> assertThat(tag.getKey()).isIn(Set.of("outcome", "error")));
    }

    @Test
    void emptyScopeIsAZeroCountCompletionButRejectionInventsNoResultCount() {
        var telemetry = telemetry();
        try (var operation = telemetry.start()) {
            operation.emptyScope();
        }
        try (var operation = telemetry.start()) {
            operation.rejected();
        }
        assertThat(
                        meters.get("secondbrain.search.requests")
                                .tag("outcome", "empty_scope")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("secondbrain.search.requests")
                                .tag("outcome", "rejected")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(meters.get("secondbrain.search.results").summary().count()).isEqualTo(1);
        assertThat(meters.get("secondbrain.search.results").summary().totalAmount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(SearchFailure.Reason.class)
    void failedSearchesHaveFixedOutcomesWithoutInventingEmptyEvidence(
            SearchFailure.Reason reason, CapturedOutput output) {
        try (var operation = telemetry().start()) {
            operation.failure(reason);
        }
        var tag = reason.name().toLowerCase(java.util.Locale.ROOT);
        assertThat(meters.get("secondbrain.search.requests").tag("outcome", tag).counter().count())
                .isEqualTo(1);
        assertThat(meters.get("secondbrain.search.duration").tag("outcome", tag).timer().count())
                .isEqualTo(1);
        assertThat(meters.get("secondbrain.search.results").summary().count()).isZero();
        assertThat(output.getAll())
                .contains("Search retrieval finished")
                .doesNotContain("Exception", "query=", "passage=");
    }

    @Test
    void inheritsTheCallersTraceWithoutChangingItsScope() {
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
            var caller = tracer.nextSpan().name("caller").start();
            var scope = tracer.withSpan(caller);
            try (scope) {
                try (var operation = telemetry.start()) {
                    assertThat(tracer.currentSpan().context().spanId())
                            .isEqualTo(caller.context().spanId());
                    assertThat(observations.getCurrentObservation()).isNull();
                    operation.success(2);
                }
                assertThat(tracer.currentSpan().context().spanId())
                        .isEqualTo(caller.context().spanId());
            } finally {
                caller.end();
            }
            var child =
                    capture.getFinishedSpanItems().stream()
                            .filter(span -> span.getName().equals("search.retrieval"))
                            .findFirst()
                            .orElseThrow();
            assertThat(child.getParentSpanId()).isEqualTo(caller.context().spanId());
            assertThat(child.getTraceId()).isEqualTo(caller.context().traceId());
            assertThat(child.getAttributes().get(AttributeKey.stringKey("outcome")))
                    .isEqualTo("success");
            assertThat(child.getEvents()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void brokenObservationStartOrStopCannotChangeTheOperation(
            boolean startFails, CapturedOutput output) {
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
                                if (startFails)
                                    throw new IllegalStateException("private-observation-sentinel");
                            }

                            @Override
                            public void onStop(Observation.Context context) {
                                if (!startFails)
                                    throw new IllegalStateException("private-observation-sentinel");
                            }
                        });
        try (var operation = telemetry().start()) {
            operation.success(1);
        }
        assertThat(
                        meters.get("secondbrain.search.requests")
                                .tag("outcome", "success")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(meters.get("secondbrain.search.results").summary().totalAmount()).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("private-observation-sentinel");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void brokenCounterOrSummaryDoesNotBlockOtherMeasurements(
            boolean counterFails, CapturedOutput output) {
        observations
                .observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters));
        var registry = spy(meters);
        if (counterFails) {
            var counter = mock(Counter.class);
            doThrow(new IllegalStateException("private-meter-sentinel")).when(counter).increment();
            doReturn(counter)
                    .when(registry)
                    .counter("secondbrain.search.requests", "outcome", "success");
        } else {
            var summary = mock(DistributionSummary.class);
            doThrow(new IllegalStateException("private-meter-sentinel")).when(summary).record(1);
            doReturn(summary).when(registry).summary("secondbrain.search.results");
        }
        try (var operation = new SearchTelemetry(registry, observations).start()) {
            operation.success(1);
        }
        assertThat(
                        meters.get("secondbrain.search.duration")
                                .tag("outcome", "success")
                                .timer()
                                .count())
                .isEqualTo(1);
        if (counterFails)
            assertThat(meters.get("secondbrain.search.results").summary().totalAmount())
                    .isEqualTo(1);
        else
            assertThat(
                            meters.get("secondbrain.search.requests")
                                    .tag("outcome", "success")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("private-meter-sentinel");
    }

    @Test
    void brokenRegistrationIsIsolatedAndNoPrivateMessageEscapes(CapturedOutput output) {
        meters.config()
                .meterFilter(
                        new MeterFilter() {
                            @Override
                            public Meter.Id map(Meter.Id id) {
                                throw new IllegalStateException("private-registry-sentinel");
                            }
                        });
        assertThatCode(
                        () -> {
                            try (var operation =
                                    new SearchTelemetry(meters, ObservationRegistry.NOOP).start()) {
                                operation.success(1);
                            }
                        })
                .doesNotThrowAnyException();
        assertThat(output.getAll())
                .contains("Search telemetry unavailable")
                .doesNotContain("private-registry-sentinel");
    }
}
