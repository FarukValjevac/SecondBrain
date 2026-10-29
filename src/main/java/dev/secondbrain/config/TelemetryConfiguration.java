package dev.secondbrain.config;

import io.micrometer.common.KeyValues;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
class TelemetryConfiguration {
    @Bean(destroyMethod = "close")
    SpanExporter safeTraceExporter(
            @Value("${secondbrain.telemetry.otlp-endpoint:}") String endpoint) {
        SpanExporter delegate = SpanExporter.composite();
        if (!endpoint.isBlank()) {
            validateEndpoint(endpoint);
            delegate =
                    OtlpHttpSpanExporter.builder()
                            .setEndpoint(endpoint)
                            .setTimeout(Duration.ofSeconds(2))
                            .setConnectTimeout(Duration.ofSeconds(1))
                            .setRetryPolicy(
                                    null) // OpenTelemetry's explicit API for disabling transport
                            // retries.
                            .build();
        }
        return new SafeSpanExporter(delegate);
    }

    static void validateEndpoint(String endpoint) {
        try {
            var uri = URI.create(endpoint);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Trace export requires an HTTP(S) endpoint without credentials, query, or fragment");
        }
    }

    @Bean
    DefaultServerRequestObservationConvention safeHttpObservationConvention() {
        return new DefaultServerRequestObservationConvention() {
            @Override
            public KeyValues getHighCardinalityKeyValues(ServerRequestObservationContext context) {
                return KeyValues.empty();
            }

            @Override
            public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
                var fields = KeyValues.empty();
                for (var field : super.getLowCardinalityKeyValues(context)) {
                    String value = field.getValue();
                    switch (field.getKey()) {
                        case "method" ->
                                value =
                                        Set.of(
                                                                "GET", "HEAD", "POST", "PUT",
                                                                "PATCH", "DELETE", "OPTIONS",
                                                                "TRACE")
                                                        .contains(value)
                                                ? value
                                                : "OTHER";
                        case "uri" ->
                                value =
                                        Set.of(
                                                                "/",
                                                                "/search",
                                                                "/**",
                                                                "/css/**",
                                                                "/documents",
                                                                "/documents/upload",
                                                                "/documents/{id}",
                                                                "/documents/{id}/retry",
                                                                "/documents/{id}/delete",
                                                                "NOT_FOUND",
                                                                "UNKNOWN",
                                                                "REDIRECTION",
                                                                "root",
                                                                "/actuator/health",
                                                                "/actuator/health/**",
                                                                "/actuator/health/{*path}",
                                                                "/actuator/metrics",
                                                                "/actuator/metrics/{requiredMetricName}")
                                                        .contains(value)
                                                ? value
                                                : "OTHER";
                        case "error", "exception" ->
                                value = value.equals("none") ? "none" : "failure";
                        case "status" -> value = value.matches("[1-5][0-9]{2}") ? value : "OTHER";
                        case "outcome" ->
                                value =
                                        Set.of(
                                                                "SUCCESS",
                                                                "CLIENT_ERROR",
                                                                "SERVER_ERROR",
                                                                "INFORMATIONAL",
                                                                "REDIRECTION",
                                                                "UNKNOWN")
                                                        .contains(value)
                                                ? value
                                                : "UNKNOWN";
                        default -> {
                            continue;
                        }
                    }
                    fields = fields.and(field.getKey(), value);
                }
                return fields;
            }
        };
    }
}
