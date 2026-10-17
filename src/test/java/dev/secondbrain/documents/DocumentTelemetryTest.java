package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.secondbrain.events.EventPublisher;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@ExtendWith(OutputCaptureExtension.class)
class DocumentTelemetryTest {
    private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final byte[] CONTENT =
            "sentinel-private-content".getBytes(StandardCharsets.UTF_8);
    private static final UUID CORRELATION = UUID.randomUUID();
    @TempDir Path directory;
    private final MockClock meterClock = new MockClock();
    private final SimpleMeterRegistry meters =
            new SimpleMeterRegistry(SimpleConfig.DEFAULT, meterClock);
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final List<Observation.Context> completed = new ArrayList<>();
    private final JdbcDocumentRepository documents = mock(JdbcDocumentRepository.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private DocumentTelemetry telemetry;
    private OriginalFileStore originals;
    private DocumentUploadPolicy policy;

    @BeforeEach
    void setUp() {
        observations
                .observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters))
                .observationHandler(
                        new ObservationHandler<Observation.Context>() {
                            @Override
                            public boolean supportsContext(Observation.Context context) {
                                return true;
                            }

                            @Override
                            public void onStop(Observation.Context context) {
                                completed.add(context);
                            }
                        });
        telemetry = new DocumentTelemetry(meters, observations);
        policy = new DocumentUploadPolicy(DataSize.ofKilobytes(1));
        originals = new OriginalFileStore(directory.toString(), policy);
    }

    @AfterEach
    void closeMeters() {
        meters.close();
    }

    @Test
    void deletionOutcomesHaveFixedLabelsAndSafeCorrelatedLogs(CapturedOutput output) {
        for (var outcome :
                List.of(DocumentTelemetry.Outcome.REQUESTED, DocumentTelemetry.Outcome.UNCHANGED)) {
            try (var operation = telemetry.deletion(CORRELATION)) {
                meterClock.add(Duration.ofMillis(10));
                operation.outcome(outcome);
            }
        }
        // A database failure leaves the operation at its safe unavailable fallback.
        telemetry.deletion(CORRELATION).close();
        for (var outcome : List.of("requested", "unchanged", "unavailable")) {
            assertThat(count("secondbrain.document.deletions", outcome)).isEqualTo(1);
            assertThat(
                            meters.get("secondbrain.document.deletion.duration")
                                    .tag("outcome", outcome)
                                    .timer()
                                    .count())
                    .isEqualTo(1);
        }
        assertThat(output.getAll())
                .contains(
                        "document_deletion",
                        "Document deletion request finished",
                        CORRELATION.toString())
                .doesNotContain("Document acceptance finished", "sentinel-private");
        for (var meter : meters.getMeters()) {
            assertThat(meter.getId().getTags().toString()).doesNotContain(CORRELATION.toString());
        }
        assertThat(completed)
                .hasSize(3)
                .allSatisfy(
                        context -> {
                            assertThat(context.getError()).isNull();
                            assertThat(
                                            context.getLowCardinalityKeyValues().stream()
                                                    .map(key -> key.getKey())
                                                    .toList())
                                    .containsExactly("outcome");
                        });
    }

    @Test
    void acceptedCounterUpdatesAfterTheTransactionReturnsAndMeasuresItsDuration()
            throws IOException {
        var transactions =
                new TransactionOperations() {
                    @Override
                    public <T> T execute(TransactionCallback<T> action) {
                        var result = action.doInTransaction(mock(TransactionStatus.class));
                        assertThat(count("secondbrain.document.uploads", "accepted")).isZero();
                        meterClock.add(Duration.ofMillis(25));
                        return result;
                    }
                };
        var receipt =
                service(telemetry, transactions)
                        .accept(
                                "sentinel-private-name.md",
                                CONTENT.length,
                                new ByteArrayInputStream(CONTENT),
                                CORRELATION);
        assertThat(receipt.state()).isEqualTo(ExtractionState.QUEUED);
        assertThat(count("secondbrain.document.uploads", "accepted")).isEqualTo(1);
        var timer =
                meters.get("secondbrain.document.upload.duration")
                        .tag("outcome", "accepted")
                        .timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(25);
        assertThat(
                        meters.get("secondbrain.document.upload.duration.active")
                                .longTaskTimer()
                                .activeTasks())
                .isZero();
        verify(publisher).publish(any());
    }

    @Test
    void rejectionAndStorageOrDatabaseFailureHaveDistinctOutcomesWithoutPrivateDetails(
            CapturedOutput output) throws IOException {
        var service = service(telemetry, directTransactions());
        assertThatThrownBy(
                        () ->
                                service.accept(
                                        "sentinel-private-name.pdf",
                                        CONTENT.length,
                                        new ByteArrayInputStream(CONTENT),
                                        CORRELATION))
                .isInstanceOf(UploadRejectedException.class);
        var broken =
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("sentinel-private-storage-error");
                    }
                };
        assertThatThrownBy(
                        () ->
                                service.accept(
                                        "sentinel-private-name.txt",
                                        CONTENT.length,
                                        broken,
                                        CORRELATION))
                .isInstanceOf(IOException.class)
                .hasMessage("sentinel-private-storage-error");
        doThrow(new DataAccessResourceFailureException("sentinel-private-db-error"))
                .when(documents)
                .insert(any());
        assertThatThrownBy(
                        () ->
                                service.accept(
                                        "sentinel-private-name.md",
                                        CONTENT.length,
                                        new ByteArrayInputStream(CONTENT),
                                        CORRELATION))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("sentinel-private-db-error");
        assertThat(count("secondbrain.document.uploads", "accepted")).isZero();
        assertThat(count("secondbrain.document.uploads", "rejected")).isEqualTo(1);
        assertThat(count("secondbrain.document.uploads", "unavailable")).isEqualTo(2);
        assertThat(
                        meters.get("secondbrain.document.upload.duration")
                                .tag("outcome", "unavailable")
                                .timer()
                                .count())
                .isEqualTo(2);
        try (var paths = Files.list(directory)) {
            assertThat(paths).isEmpty();
        }
        assertThat(completed).hasSize(3);
        for (var context : completed) {
            assertThat(context.getError()).isNull();
            assertThat(
                            context.getLowCardinalityKeyValues().stream()
                                    .map(key -> key.getKey())
                                    .toList())
                    .containsExactly("outcome");
            assertThat(
                            context.getHighCardinalityKeyValues().stream()
                                    .filter(key -> key.getKey().equals("correlation_id"))
                                    .findFirst()
                                    .orElseThrow()
                                    .getValue())
                    .isEqualTo(CORRELATION.toString());
            assertThat(context.getAllKeyValues().toString()).doesNotContain("sentinel-private");
        }
        for (var meter : meters.getMeters()) {
            assertThat(meter.getId().getTags().toString())
                    .doesNotContain("sentinel-private", CORRELATION.toString());
        }
        assertThat(output.getAll())
                .contains(CORRELATION.toString(), "Document acceptance finished")
                .doesNotContain("sentinel-private", directory.toString());
    }

    @Test
    void cleanupCountsCommittedDeletionsEvenWhenALaterOwnershipLookupFails(CapturedOutput output)
            throws IOException {
        for (int i = 0; i < 2; i++) {
            var path = Files.write(directory.resolve(UUID.randomUUID() + ".original"), CONTENT);
            Files.setLastModifiedTime(path, FileTime.from(NOW.minus(Duration.ofHours(2))));
        }
        var lookups = new AtomicInteger();
        when(documents.ownsStorageId(any()))
                .thenAnswer(
                        invocation -> {
                            if (lookups.incrementAndGet() == 2) {
                                throw new DataAccessResourceFailureException(
                                        "sentinel-private-cleanup-error");
                            }
                            return false;
                        });
        var settings =
                new OriginalCleanupProperties(Duration.ofHours(1), Duration.ofMinutes(1), 100);
        try (var cleaner =
                new OriginalFileReconciler(originals, documents, settings, CLOCK, telemetry)) {
            cleaner.cleanup();
            assertThat(count("secondbrain.document.cleanup.batches", "failed")).isEqualTo(1);
            assertThat(meters.get("secondbrain.document.cleanup.deleted").counter().count())
                    .isEqualTo(1);
            try (var paths = Files.list(directory)) {
                assertThat(paths.count()).isEqualTo(1);
            }
            doReturn(false).when(documents).ownsStorageId(any());
            cleaner.cleanup();
            assertThat(count("secondbrain.document.cleanup.batches", "completed")).isEqualTo(1);
            assertThat(meters.get("secondbrain.document.cleanup.deleted").counter().count())
                    .isEqualTo(2);
            cleaner.cleanup();
            assertThat(meters.get("secondbrain.document.cleanup.deleted").counter().count())
                    .isEqualTo(2);
            assertThat(
                            meters.get("secondbrain.document.cleanup.duration")
                                    .tag("outcome", "failed")
                                    .timer()
                                    .count())
                    .isEqualTo(1);
        }
        assertThat(output.getAll()).doesNotContain("sentinel-private", directory.toString());
    }

    @Test
    void brokenMetricsAndObservationHandlersCannotChangeAcceptanceRejectionOrCleanup(
            CapturedOutput output) throws IOException {
        var brokenMeters = mock(MeterRegistry.class);
        when(brokenMeters.counter(any(String.class), any(String[].class)))
                .thenThrow(new IllegalStateException("sentinel-private-meter-error"));
        for (var phase : List.of("start", "stop")) {
            var brokenObservations = ObservationRegistry.create();
            brokenObservations
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
                                        throw new IllegalStateException(
                                                "sentinel-private-handler-error");
                                }

                                @Override
                                public void onStop(Observation.Context context) {
                                    if (phase.equals("stop"))
                                        throw new IllegalStateException(
                                                "sentinel-private-handler-error");
                                }
                            });
            var brokenTelemetry = new DocumentTelemetry(brokenMeters, brokenObservations);
            assertThatCode(
                            () -> {
                                try (var operation = brokenTelemetry.deletion(CORRELATION)) {
                                    operation.outcome(DocumentTelemetry.Outcome.REQUESTED);
                                }
                            })
                    .doesNotThrowAnyException();
            var service = service(brokenTelemetry, directTransactions());
            assertThatCode(
                            () ->
                                    service.accept(
                                            "note.txt",
                                            CONTENT.length,
                                            new ByteArrayInputStream(CONTENT),
                                            CORRELATION))
                    .doesNotThrowAnyException();
            assertThatThrownBy(
                            () ->
                                    service.accept(
                                            "note.pdf",
                                            CONTENT.length,
                                            new ByteArrayInputStream(CONTENT),
                                            CORRELATION))
                    .isInstanceOf(UploadRejectedException.class);
            var settings =
                    new OriginalCleanupProperties(Duration.ofHours(1), Duration.ofMinutes(1), 100);
            try (var cleaner =
                    new OriginalFileReconciler(
                            originals, documents, settings, CLOCK, brokenTelemetry)) {
                cleaner.cleanup();
            }
            try (var paths = Files.list(directory)) {
                assertThat(paths).isEmpty();
            }
            assertThat(brokenObservations.getCurrentObservation()).isNull();
        }
        assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @Test
    void observationsShareTheRequestTraceAndCorrelationWithoutReplacingItsScope() {
        var exporter = InMemorySpanExporter.create();
        try (var provider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build()) {
            var tracer =
                    new OtelTracer(
                            provider.get("test"), new OtelCurrentTraceContext(), event -> {});
            observations
                    .observationConfig()
                    .observationHandler(new DefaultTracingObservationHandler(tracer));
            var request = Observation.start("http.request", observations);
            var scope = request.openScope();
            try (scope) {
                try (var upload = telemetry.upload(CORRELATION)) {
                    assertThat(observations.getCurrentObservation()).isSameAs(request);
                    upload.outcome(DocumentTelemetry.Outcome.ACCEPTED);
                }
                assertThat(observations.getCurrentObservation()).isSameAs(request);
            } finally {
                request.stop();
            }
            assertThat(observations.getCurrentObservation()).isNull();
            assertThat(tracer.currentSpan()).isNull();
            var spans = exporter.getFinishedSpanItems();
            var parent =
                    spans.stream()
                            .filter(span -> span.getName().equals("http.request"))
                            .findFirst()
                            .orElseThrow();
            var upload =
                    spans.stream()
                            .filter(span -> span.getName().equals("document.upload"))
                            .findFirst()
                            .orElseThrow();
            assertThat(upload.getTraceId()).isEqualTo(parent.getTraceId());
            assertThat(upload.getParentSpanId()).isEqualTo(parent.getSpanId());
            assertThat(upload.getAttributes().get(AttributeKey.stringKey("correlation_id")))
                    .isEqualTo(CORRELATION.toString());
            assertThat(upload.getAttributes().get(AttributeKey.stringKey("outcome")))
                    .isEqualTo("accepted");
            assertThat(upload.getEvents()).isEmpty();
        }
    }

    @Test
    void closingAnOperationTwiceDoesNotDoubleCount() {
        var operation = telemetry.upload(CORRELATION);
        operation.outcome(DocumentTelemetry.Outcome.ACCEPTED);
        operation.close();
        operation.close();
        assertThat(count("secondbrain.document.uploads", "accepted")).isEqualTo(1);
        assertThat(
                        meters.get("secondbrain.document.upload.duration")
                                .tag("outcome", "accepted")
                                .timer()
                                .count())
                .isEqualTo(1);
    }

    private double count(String name, String outcome) {
        return meters.get(name).tag("outcome", outcome).counter().count();
    }

    private DocumentAcceptanceService service(
            DocumentTelemetry measurements, TransactionOperations transactions) {
        return new DocumentAcceptanceService(
                policy, originals, documents, publisher, transactions, CLOCK, measurements);
    }

    private static TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(mock(TransactionStatus.class));
            }
        };
    }
}
