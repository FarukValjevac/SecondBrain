package dev.secondbrain.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class LocalMetricsConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    MetricsAutoConfiguration.class,
                                    CompositeMeterRegistryAutoConfiguration.class,
                                    SimpleMetricsExportAutoConfiguration.class,
                                    OtlpMetricsExportAutoConfiguration.class))
                    .withUserConfiguration(LocalMetricsConfiguration.class)
                    .withPropertyValues("management.otlp.metrics.export.enabled=false");

    @Test
    void disabledExportCreatesNoTransportOrPushRegistry() {
        context.run(
                app -> {
                    assertThat(app)
                            .doesNotHaveBean(LocalMetricsSender.class)
                            .doesNotHaveBean(OtlpMeterRegistry.class);
                    assertThat(app.getBean(io.micrometer.core.instrument.MeterRegistry.class))
                            .isNotNull();
                });
    }

    @Test
    void enabledExportUsesOnlyTheConfiguredBoundedSenderAndRegistry() {
        context.withPropertyValues("management.otlp.metrics.export.enabled=true")
                .run(
                        app -> {
                            assertThat(app)
                                    .hasSingleBean(LocalMetricsSender.class)
                                    .hasSingleBean(OtlpMeterRegistry.class);
                            var config = app.getBean(OtlpConfig.class);
                            assertThat(config.step()).isEqualTo(Duration.ofSeconds(10));
                            assertThat(config.batchSize()).isEqualTo(512);
                            assertThat(config.baseTimeUnit()).isEqualTo(TimeUnit.SECONDS);
                            assertThat(config.headers()).isEmpty();
                            assertThat(config.resourceAttributes())
                                    .isEqualTo(Map.of("service.name", "secondbrain"));
                        });
    }

    @Test
    void serializesSafeMetricsAndSecondsWithoutPrivateMetadata() throws Exception {
        var payload = new AtomicReference<byte[]>();
        var configuration = new LocalMetricsConfiguration();
        var config = configuration.localOtlpConfig("http://127.0.0.1:4318/v1/metrics");
        var registry =
                OtlpMeterRegistry.builder(config)
                        .metricsSender(request -> payload.set(request.getMetricsData()))
                        .build();
        try {
            registry.stop();
            configuration.safeLocalMetrics().customize(registry);
            Counter.builder("secondbrain.document.uploads")
                    .description("private-description-sentinel")
                    .tags("outcome", "accepted")
                    .register(registry)
                    .increment();
            registry.counter(
                            "secondbrain.document.uploads", "filename", "private-filename-sentinel")
                    .increment();
            registry.counter("secondbrain.document.uploads", "outcome", "private-outcome-sentinel")
                    .increment();
            registry.counter("private-name-sentinel").increment();
            Timer.builder("secondbrain.embedding.request.duration")
                    .tags("outcome", "success")
                    .register(registry)
                    .record(Duration.ofMillis(250));
            registry.counter("secondbrain.chat.requests", "outcome", "quota_exhausted").increment();
            registry.counter("secondbrain.question.answers", "outcome", "answered").increment();
            io.micrometer.core.instrument.Gauge.builder(
                            "secondbrain.document.deletion.backlog", () -> 2.0)
                    .tag("state", "failed")
                    .strongReference(true)
                    .register(registry);
            io.micrometer.core.instrument.Gauge.builder(
                            "secondbrain.document.deletion.backlog.oldest", () -> 90.0)
                    .baseUnit("seconds")
                    .strongReference(true)
                    .register(registry);
            ReflectionTestUtils.invokeMethod(registry, "publish");
            var request = ExportMetricsServiceRequest.parseFrom(payload.get());
            assertThat(request.toString()).doesNotContain("private-", "Authorization", "host.name");
            var resource = request.getResourceMetrics(0);
            assertThat(resource.getResource().getAttributesList())
                    .anySatisfy(
                            attribute -> {
                                assertThat(attribute.getKey()).isEqualTo("service.name");
                                assertThat(attribute.getValue().getStringValue())
                                        .isEqualTo("secondbrain");
                            });
            var metrics = resource.getScopeMetrics(0).getMetricsList();
            assertThat(metrics).hasSize(6);
            var oldest =
                    metrics.stream()
                            .filter(metric -> metric.getName().endsWith("oldest"))
                            .findFirst()
                            .orElseThrow();
            assertThat(oldest.getUnit()).isEqualTo("seconds");
            assertThat(oldest.getGauge().getDataPoints(0).getAsDouble()).isEqualTo(90.0);
            var timer =
                    metrics.stream()
                            .filter(metric -> metric.getName().endsWith("duration"))
                            .findFirst()
                            .orElseThrow();
            assertThat(timer.getUnit()).isEqualTo("seconds");
            assertThat(timer.getHistogram().getDataPoints(0).getSum()).isEqualTo(0.25);
            assertThat(timer.getHistogram().getDataPoints(0).getCount()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    @Test
    void capsTheExportRegistryWithoutChangingAnotherRegistry() {
        var configuration = new LocalMetricsConfiguration();
        var registry =
                OtlpMeterRegistry.builder(
                                configuration.localOtlpConfig("http://127.0.0.1:4318/v1/metrics"))
                        .metricsSender(request -> {})
                        .build();
        var local = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            registry.stop();
            configuration.safeLocalMetrics().customize(registry);
            // Known finite label combinations exercise the hard cap as well as the allowlist.
            var tags = new ArrayList<io.micrometer.core.instrument.Tag>();
            for (var outcome :
                    new String[] {
                        "accepted",
                        "failed",
                        "completed",
                        "requested",
                        "unchanged",
                        "success",
                        "retry",
                        "skipped"
                    })
                for (var state :
                        new String[] {
                            "ready", "delayed", "retrying", "running", "failed", "pending"
                        })
                    for (var kind : new String[] {"input", "output", "total"})
                        for (var name :
                                new String[] {
                                    "secondbrain.document.uploads",
                                    "secondbrain.chat.requests",
                                    "secondbrain.search.requests",
                                    "secondbrain.question.answers"
                                }) {
                            tags.clear();
                            tags.add(io.micrometer.core.instrument.Tag.of("outcome", outcome));
                            tags.add(io.micrometer.core.instrument.Tag.of("state", state));
                            tags.add(io.micrometer.core.instrument.Tag.of("kind", kind));
                            registry.counter(name, tags).increment();
                        }
            assertThat(registry.getMeters()).hasSize(512);
            local.counter("private-local-only", Tags.of("query", "private-sentinel")).increment();
            assertThat(local.getMeters()).hasSize(1);
        } finally {
            registry.close();
            local.close();
        }
    }

    @Test
    void refusesNonlocalOrCredentialBearingMetricsEndpointsWithoutEchoingThem() {
        for (String endpoint :
                new String[] {
                    "https://example.com/v1/metrics",
                    "http://private-sentinel@localhost/v1/metrics",
                    "http://localhost/v1/metrics?private-sentinel",
                    "http://localhost/private-sentinel"
                })
            assertThatThrownBy(() -> new LocalMetricsSender(endpoint))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("private-sentinel");
    }
}
