package dev.secondbrain.config;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.UUID;

class TelemetryPrivacyTest {
    @Test
    void httpObservationLabelsAreBoundedAndNeverContainRawInput() {
        var request =
                new org.springframework.mock.web.MockHttpServletRequest(
                        "sentinel-private-method", "/sentinel-private-path");
        request.setQueryString("q=sentinel-private-query");
        var context =
                new org.springframework.http.server.observation.ServerRequestObservationContext(
                        request, new org.springframework.mock.web.MockHttpServletResponse());
        context.setPathPattern("/sentinel-private-pattern");
        context.setError(new IllegalStateException("sentinel-private-error"));
        var convention = new TelemetryConfiguration().safeHttpObservationConvention();
        Assertions.assertThat(convention.getHighCardinalityKeyValues(context)).isEmpty();
        var tags = convention.getLowCardinalityKeyValues(context);
        Assertions.assertThat(tags.toString()).doesNotContain("sentinel-private");
        Assertions.assertThat(tags)
                .anySatisfy(
                        tag -> {
                            Assertions.assertThat(tag.getKey()).isEqualTo("uri");
                            Assertions.assertThat(tag.getValue()).isEqualTo("OTHER");
                        });
        request.setMethod("POST");
        for (String route :
                new String[] {"/documents", "/documents/upload", "/documents/{id}/delete"}) {
            context.setPathPattern(route);
            Assertions.assertThat(convention.getLowCardinalityKeyValues(context))
                    .anySatisfy(
                            tag -> {
                                Assertions.assertThat(tag.getKey()).isEqualTo("uri");
                                Assertions.assertThat(tag.getValue()).isEqualTo(route);
                            });
        }
    }

    @Test
    void exportsOnlyAllowlistedIdsAndDropsPrivateContentFromEverySpanSurface() {
        var capture = InMemorySpanExporter.create();
        var safe = new SafeSpanExporter(capture);
        try (var sdk =
                SdkTracerProvider.builder()
                        .setResource(
                                Resource.create(
                                        Attributes.builder()
                                                .put("secret", "sentinel-private-resource")
                                                .build()))
                        .addSpanProcessor(SimpleSpanProcessor.create(safe))
                        .build()) {
            var remote =
                    SpanContext.createFromRemoteParent(
                            "1".repeat(32),
                            "2".repeat(16),
                            TraceFlags.getSampled(),
                            TraceState.builder().put("secret", "sentinel-private-baggage").build());
            var span =
                    sdk.get("sentinel-private-scope")
                            .spanBuilder("sentinel-private-query")
                            .addLink(
                                    remote,
                                    Attributes.builder()
                                            .put("secret", "sentinel-private-link")
                                            .build())
                            .startSpan();
            String id = UUID.randomUUID().toString();
            span.setAttribute("correlation_id", id);
            span.setAttribute("event_id", "sentinel-private-invalid-id");
            span.setAttribute("http.url", "sentinel-private-url");
            span.setAttribute("outcome", "sentinel-private-outcome");
            span.recordException(new IllegalStateException("sentinel-private-error"));
            span.setStatus(StatusCode.ERROR, "sentinel-private-status");
            span.end();
            var exported = capture.getFinishedSpanItems().getFirst();
            Assertions.assertThat(exported.getAttributes().size()).isEqualTo(1);
            Assertions.assertThat(exported.getName()).isEqualTo("application.operation");
            Assertions.assertThat(exported.getEvents()).isEmpty();
            Assertions.assertThat(exported.getStatus().getDescription()).isEmpty();
            Assertions.assertThat(exported.getLinks().getFirst().getAttributes().isEmpty())
                    .isTrue();
            Assertions.assertThat(
                            exported.getLinks()
                                    .getFirst()
                                    .getSpanContext()
                                    .getTraceState()
                                    .isEmpty())
                    .isTrue();
            Assertions.assertThat(exported.getSpanContext().getTraceState().isEmpty()).isTrue();
            Assertions.assertThat(
                            exported.getName()
                                    + exported.getAttributes()
                                    + exported.getEvents()
                                    + exported.getLinks()
                                    + exported.getStatus()
                                    + exported.getResource()
                                    + exported.getInstrumentationScopeInfo())
                    .doesNotContain("sentinel-private");
        }
    }

    @Test
    void preservesDocumentOperationNamesAndFixedOutcomesWithoutPrivateSpanFields() {
        var capture = InMemorySpanExporter.create();
        try (var sdk =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(new SafeSpanExporter(capture)))
                        .build()) {
            for (var operation :
                    new String[][] {
                        {"document.upload", "accepted"},
                        {"document.upload", "rejected"},
                        {"document.upload", "unavailable"},
                        {"document.cleanup", "completed"},
                        {"document.cleanup", "failed"},
                        {"document.deletion", "requested"},
                        {"document.deletion", "unchanged"},
                        {"document.deletion", "unavailable"},
                        {"document.indexing.request", "requested"},
                        {"document.indexing.request", "unchanged"},
                        {"document.indexing.request", "unavailable"}
                    }) {
                var span = sdk.get("test").spanBuilder(operation[0]).startSpan();
                span.setAttribute("outcome", operation[1]);
                span.setAttribute("filename", "sentinel-private-document-name");
                span.recordException(new IllegalStateException("sentinel-private-storage-error"));
                span.end();
                var exported = capture.getFinishedSpanItems().getLast();
                Assertions.assertThat(exported.getName()).isEqualTo(operation[0]);
                Assertions.assertThat(exported.getAttributes().size()).isEqualTo(1);
                Assertions.assertThat(
                                exported.getAttributes()
                                        .get(
                                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                                        "outcome")))
                        .isEqualTo(operation[1]);
                Assertions.assertThat(exported.getEvents()).isEmpty();
                Assertions.assertThat(exported.getAttributes().toString())
                        .doesNotContain("sentinel-private");
            }
        }
    }

    @Test
    void exporterFailuresNeverEscapeIntoSpanCompletion() {
        var delegate = Mockito.mock(SpanExporter.class);
        Mockito.when(delegate.shutdown())
                .thenReturn(io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess());
        Mockito.when(delegate.export(ArgumentMatchers.anyCollection()))
                .thenThrow(new IllegalStateException("sentinel-private-export-error"));
        var safe = new SafeSpanExporter(delegate);
        try (var sdk =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(safe))
                        .build()) {
            Assertions.assertThatCode(
                            () -> sdk.get("test").spanBuilder("event.delivery").startSpan().end())
                    .doesNotThrowAnyException();
            Mockito.verify(delegate).export(ArgumentMatchers.anyCollection());
        }
        safe.close();
        Mockito.verify(delegate, Mockito.times(1)).shutdown();
    }

    @Test
    void exportIsOptInAndEndpointValidationDoesNotEchoCredentials() {
        var configuration = new TelemetryConfiguration();
        try (var disabled = configuration.safeTraceExporter("")) {
            Assertions.assertThat(disabled.export(java.util.List.of()).isSuccess()).isTrue();
        }
        for (String endpoint :
                new String[] {
                    "file:///private",
                    "http://user:sentinel-private@localhost/v1/traces",
                    "https://localhost/?sentinel-private"
                }) {
            Assertions.assertThatThrownBy(() -> configuration.safeTraceExporter(endpoint))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("sentinel-private");
        }
        Assertions.assertThatCode(
                        () ->
                                TelemetryConfiguration.validateEndpoint(
                                        "http://127.0.0.1:4318/v1/traces"))
                .doesNotThrowAnyException();
        var configured = configuration.safeTraceExporter("http://127.0.0.1:4318/v1/traces");
        try (configured) {
            Assertions.assertThat(configured).isInstanceOf(SafeSpanExporter.class);
        }
    }
}
