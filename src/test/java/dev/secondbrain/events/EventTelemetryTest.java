package dev.secondbrain.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

@ExtendWith(OutputCaptureExtension.class)
class EventTelemetryTest {
    @Test
    void pinsNamesUnitsAndBoundedOutcomeLabels() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new EventMetrics(registry);
            for (var outcome : EventMetrics.Outcome.values()) {
                metrics.record(outcome, Duration.ofMillis(250));
            }
            Assertions.assertThat(registry.find("secondbrain.event.delivery.attempts").counters())
                    .hasSize(4);
            for (var counter : registry.find("secondbrain.event.delivery.attempts").counters()) {
                Assertions.assertThat(counter.count()).isEqualTo(1);
                Assertions.assertThat(counter.getId().getTags()).hasSize(1);
                Assertions.assertThat(counter.getId().getTag("outcome"))
                        .isIn("completed", "skipped", "retry", "failed");
            }
            Assertions.assertThat(
                            registry.get("secondbrain.event.delivery.duration")
                                    .tag("outcome", "completed")
                                    .timer()
                                    .totalTime(java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(0.25);
        } finally {
            registry.close();
        }
    }

    @Test
    void cachesDatabaseProjectionAndReportsUnavailableInsteadOfZero() {
        var store = Mockito.mock(JdbcEventStore.class);
        var clock = new MutableClock(Instant.EPOCH);
        Mockito.when(store.backlog())
                .thenAnswer(
                        invocation -> {
                            clock.advance(Duration.ofSeconds(5));
                            return new JdbcEventStore.Backlog(2, 3, 1, 4, 5, 10);
                        })
                .thenThrow(new IllegalStateException("sentinel-private"))
                .thenReturn(new JdbcEventStore.Backlog(1, 0, 0, 0, 0, 20));
        var registry = new SimpleMeterRegistry();
        try {
            var beans = new StaticListableBeanFactory();
            new EventBacklogMetrics(
                    store, registry, clock, beans.getBeanProvider(DurableEventWorker.class));
            Assertions.assertThat(
                            registry.get("secondbrain.event.backlog")
                                    .tag("state", "ready")
                                    .gauge()
                                    .value())
                    .isEqualTo(2);
            Assertions.assertThat(registry.get("secondbrain.event.backlog.oldest").gauge().value())
                    .isEqualTo(10);
            Assertions.assertThat(
                            registry.get("secondbrain.event.backlog.available").gauge().value())
                    .isEqualTo(1);
            Mockito.verify(store, Mockito.times(1)).backlog();
            clock.advance(Duration.ofSeconds(5));
            Assertions.assertThat(
                            registry.get("secondbrain.event.backlog")
                                    .tag("state", "ready")
                                    .gauge()
                                    .value())
                    .isNaN();
            Assertions.assertThat(
                            registry.get("secondbrain.event.backlog.available").gauge().value())
                    .isZero();
            clock.advance(Duration.ofSeconds(5));
            Assertions.assertThat(
                            registry.get("secondbrain.event.backlog")
                                    .tag("state", "ready")
                                    .gauge()
                                    .value())
                    .isEqualTo(1);
            Assertions.assertThat(
                            registry.get("secondbrain.event.backlog.oldest")
                                    .gauge()
                                    .getId()
                                    .getBaseUnit())
                    .isEqualTo("seconds");
            for (var meter : registry.getMeters()) {
                Assertions.assertThat(
                                meter.getId().getTags().stream()
                                        .map(tag -> tag.getKey())
                                        .collect(Collectors.toSet()))
                        .isSubsetOf(Set.of("state"));
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void brokenMetricRecordingCannotFailTheCaller() {
        var delegate = new SimpleMeterRegistry();
        try {
            var registry = Mockito.spy(delegate);
            var counter = Mockito.mock(Counter.class);
            Mockito.doThrow(new IllegalStateException("sentinel-private"))
                    .when(counter)
                    .increment();
            Mockito.doReturn(counter)
                    .when(registry)
                    .counter("secondbrain.event.delivery.attempts", "outcome", "completed");
            var metrics = new EventMetrics(registry);
            Assertions.assertThatCode(
                            () -> metrics.record(EventMetrics.Outcome.COMPLETED, Duration.ZERO))
                    .doesNotThrowAnyException();
            Assertions.assertThat(
                            registry.get("secondbrain.event.delivery.duration")
                                    .tag("outcome", "completed")
                                    .timer()
                                    .count())
                    .isEqualTo(1);
        } finally {
            delegate.close();
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = Meter.Type.class,
            names = {"COUNTER", "TIMER", "GAUGE"})
    void brokenRegistrationDoesNotPreventConstructionOrHealthyMeterRecording(
            Meter.Type brokenType, CapturedOutput output) {
        var registry = new SimpleMeterRegistry();
        try {
            registry.config()
                    .meterFilter(
                            new MeterFilter() {
                                @Override
                                public Meter.Id map(Meter.Id id) {
                                    if (id.getType() == brokenType) {
                                        throw new IllegalStateException(
                                                "sentinel-private-registry");
                                    }
                                    return id;
                                }
                            });
            if (brokenType == Meter.Type.GAUGE) {
                var store = Mockito.mock(JdbcEventStore.class);
                var beans = new StaticListableBeanFactory();
                Assertions.assertThatCode(
                                () ->
                                        new EventBacklogMetrics(
                                                store,
                                                registry,
                                                Clock.systemUTC(),
                                                beans.getBeanProvider(DurableEventWorker.class)))
                        .doesNotThrowAnyException();
                Mockito.verifyNoInteractions(store);
            } else {
                var metrics = new EventMetrics(registry);
                for (var outcome : EventMetrics.Outcome.values()) {
                    Assertions.assertThatCode(() -> metrics.record(outcome, Duration.ZERO))
                            .doesNotThrowAnyException();
                }
                if (brokenType == Meter.Type.COUNTER) {
                    Assertions.assertThat(
                                    registry.find("secondbrain.event.delivery.duration").timers())
                            .hasSize(4)
                            .allSatisfy(timer -> Assertions.assertThat(timer.count()).isEqualTo(1));
                } else {
                    Assertions.assertThat(
                                    registry.find("secondbrain.event.delivery.attempts").counters())
                            .hasSize(4)
                            .allSatisfy(
                                    counter -> Assertions.assertThat(counter.count()).isEqualTo(1));
                }
            }
        } finally {
            registry.close();
        }
        Assertions.assertThat(output.getAll())
                .contains("TELEMETRY_FAILED")
                .doesNotContain("sentinel-private");
    }

    @Test
    void workerReadinessDependsOnItsSchedulerUnlessDeliberatelyDisabled() {
        var beans = new StaticListableBeanFactory();
        var provider = beans.getBeanProvider(DurableEventWorker.class);
        var health = new EventWorkerHealthIndicator(provider, true, true);
        Assertions.assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
        var worker = Mockito.mock(DurableEventWorker.class);
        beans.addBean("worker", worker);
        Mockito.when(worker.isOperational()).thenReturn(true);
        Assertions.assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
        Mockito.when(worker.isOperational()).thenReturn(false);
        Assertions.assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
        Assertions.assertThat(
                        new EventWorkerHealthIndicator(provider, false, true)
                                .health()
                                .getStatus()
                                .getCode())
                .isEqualTo("UP");
        Assertions.assertThat(
                        new EventWorkerHealthIndicator(provider, true, false)
                                .health()
                                .getStatus()
                                .getCode())
                .isEqualTo("UP");
    }
}
