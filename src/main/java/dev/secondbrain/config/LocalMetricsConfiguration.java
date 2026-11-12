package dev.secondbrain.config;

import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.registry.otlp.AggregationTemporality;
import io.micrometer.registry.otlp.CompressionMode;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.micrometer.metrics.actuate.endpoint.MetricsEndpoint;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "management.otlp.metrics.export.enabled", havingValue = "true")
class LocalMetricsConfiguration {
    @Bean
    @ConditionalOnMissingBean(SimpleMeterRegistry.class)
    SimpleMeterRegistry localInspectionRegistry() {
        // Export filtering must not remove JVM/HTTP diagnostics from local Actuator inspection.
        return new SimpleMeterRegistry();
    }

    @Bean
    MetricsEndpoint localMetricsEndpoint(SimpleMeterRegistry localInspectionRegistry) {
        // Composite reads can select an export-denied NoopMeter. Actuator must inspect the full
        // local registry, while recording still fans out through Boot's composite registry.
        return new MetricsEndpoint(localInspectionRegistry);
    }

    @Bean(destroyMethod = "close")
    LocalMetricsSender localMetricsSender(
            @Value("${management.otlp.metrics.export.url:http://127.0.0.1:4318/v1/metrics}")
                    String endpoint) {
        return new LocalMetricsSender(endpoint);
    }

    @Bean
    OtlpConfig localOtlpConfig(
            @Value("${management.otlp.metrics.export.url:http://127.0.0.1:4318/v1/metrics}")
                    String endpoint) {
        return new OtlpConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public String url() {
                return endpoint;
            }

            @Override
            public Duration step() {
                return Duration.ofSeconds(10);
            }

            @Override
            public int batchSize() {
                return 512;
            }

            @Override
            public Map<String, String> resourceAttributes() {
                return Map.of("service.name", "secondbrain");
            }

            @Override
            public Map<String, String> headers() {
                return Map.of();
            }

            @Override
            public CompressionMode compressionMode() {
                return CompressionMode.NONE;
            }

            @Override
            public AggregationTemporality aggregationTemporality() {
                return AggregationTemporality.CUMULATIVE;
            }

            @Override
            public TimeUnit baseTimeUnit() {
                return TimeUnit.SECONDS;
            }

            @Override
            public boolean publishMaxGaugeForHistograms() {
                return false;
            }
        };
    }

    @Bean
    MeterRegistryCustomizer<OtlpMeterRegistry> safeLocalMetrics() {
        return registry ->
                registry.config()
                        .meterFilter(new SafeMetricsFilter())
                        .meterFilter(MeterFilter.maximumAllowableMetrics(512));
    }
}
