package dev.secondbrain.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.documents.DocumentDeletionService;
import dev.secondbrain.documents.DocumentIndexingService;
import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.PdfFixtures;
import dev.secondbrain.embeddings.EmbeddingFailure;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/** Real HTTP, storage, database, poller and stage handlers; inference never leaves the JVM. */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class DocumentIndexingWorkflowIT {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    private static final List<String> STAGES =
            List.of(
                    "DocumentAccepted",
                    "TextExtracted",
                    "ReindexRequested",
                    "PassagesPrepared",
                    "IndexPublished");
    @TempDir private Path storage;
    private final MutableClock clock =
            new MutableClock(Instant.parse("2030-01-01T00:00:00.123456Z"));

    @AfterEach
    void clearOnlyIsolatedFixturesAfterWorkersClose() {
        JdbcClient.create(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()))
                .sql("TRUNCATE documents, event_keys CASCADE")
                .update();
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void httpUploadsReachOneCorrelatedReadyIndexWithoutWaitingForInference(
            Format format, CapturedOutput output) throws Exception {
        var inference = new ControlledProvider(true, false);
        try (var traces = new TraceProbe();
                var context = start(inference, traces);
                var client = client()) {
            var correlation = UUID.randomUUID();
            UUID id = upload(context, client, format, correlation);
            try {
                assertThat(inference.entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(active(context)).isZero();
                assertThat(timer(context)).isZero();
                assertThat(facts(context, id)).containsExactlyElementsOf(STAGES.subList(0, 4));
            } finally {
                inference.release.countDown();
            }
            awaitReady(context, id);
            assertThat(inference.calls.get()).isEqualTo(rows(context, "document_passages", id));
            assertLocationsAndIdentity(context, id, format);
            assertCorrelation(context, id, correlation);
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertStageTraces(traces, context, id, correlation));
            var versions = context.getBean(DocumentIndexingService.class);
            int calls = inference.calls.get();
            assertThat(versions.request(id, 1, correlation)).isPresent();
            // A repeat reuses the existing logical request/delivery, including its completed state.
            assertThat(facts(context, id)).containsExactlyElementsOf(STAGES);
            assertThat(inference.calls.get()).isEqualTo(calls);
            assertThat(timer(context)).isEqualTo(1);
            assertThat(rows(context, "document_embeddings", id))
                    .isEqualTo(rows(context, "document_passages", id));
            assertThat(
                            meters(context)
                                    .get("secondbrain.event.delivery.attempts")
                                    .tag("outcome", "completed")
                                    .counter()
                                    .count())
                    .isEqualTo(4);
            for (var meter : meters(context).getMeters()) {
                if (meter.getId().getName().startsWith("secondbrain.index."))
                    assertThat(meter.getId().getTags()).isEmpty();
            }
            assertThat(output.getAll())
                    .contains(correlation.toString())
                    .doesNotContain("Private synthetic source", "private-workflow");
        }
    }

    @Test
    void quotaDelaySurvivesRestartAndResumesOnlyMissingBatchesWithoutResettingAttempts()
            throws Exception {
        UUID id;
        var first = new ControlledProvider(false, true);
        try (var traces = new TraceProbe();
                var context = start(first, traces);
                var client = client()) {
            id = upload(context, client, Format.MARKDOWN, UUID.randomUUID());
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(
                            () -> {
                                assertThat(embeddingState(context, id)).isEqualTo("PENDING");
                                assertThat(first.calls.get()).isEqualTo(2);
                                assertThat(rows(context, "document_embeddings", id)).isEqualTo(1);
                            });
            assertThat(attempts(context, id)).isEqualTo(1);
            assertThat(active(context)).isZero();
            assertThat(timer(context)).isZero();
        }
        var resumed = new ControlledProvider(false, false);
        try (var traces = new TraceProbe();
                var context = start(resumed, traces)) {
            await().during(Duration.ofMillis(200))
                    .atMost(Duration.ofSeconds(3))
                    .untilAsserted(
                            () -> {
                                assertThat(resumed.calls.get()).isZero();
                                assertThat(attempts(context, id)).isEqualTo(1);
                                assertThat(embeddingState(context, id)).isEqualTo("PENDING");
                            });
            clock.advance(Duration.ofHours(1));
            awaitReady(context, id);
            assertThat(attempts(context, id)).isEqualTo(2);
            assertThat(resumed.calls.get()).isEqualTo(rows(context, "document_passages", id) - 1);
            assertThat(resumed.inputs).doesNotContain(first.inputs.getFirst());
            assertThat(facts(context, id)).containsExactlyElementsOf(STAGES);
        }
        var idle = new ControlledProvider(false, false);
        try (var traces = new TraceProbe();
                var context = start(idle, traces)) {
            assertThat(active(context)).isEqualTo(1);
            assertThat(timer(context)).isZero();
            assertThat(attempts(context, id)).isEqualTo(2);
            await().during(Duration.ofMillis(200))
                    .atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(idle.calls.get()).isZero());
        }
    }

    @Test
    void deletionDuringAutomaticInferencePreventsPublicationAndCleansAllContentAcrossRestart()
            throws Exception {
        UUID id;
        Path original;
        var inference = new ControlledProvider(true, false);
        try (var traces = new TraceProbe();
                var context = start(inference, traces);
                var client = client()) {
            id = upload(context, client, Format.TXT, UUID.randomUUID());
            try {
                assertThat(inference.entered.await(10, TimeUnit.SECONDS)).isTrue();
                original =
                        storage.resolve(
                                jdbc(context)
                                                .sql(
                                                        "SELECT storage_key FROM documents WHERE document_id = :id")
                                                .param("id", id)
                                                .query(UUID.class)
                                                .single()
                                        + ".original");
                assertThat(
                                context.getBean(DocumentDeletionService.class)
                                        .request(id, UUID.randomUUID()))
                        .isTrue();
                assertThat(context.getBean(DocumentInspectionService.class).find(id)).isEmpty();
                assertThat(active(context)).isZero();
            } finally {
                inference.release.countDown();
            }
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertCleaned(context, id, original));
            assertThat(inference.calls.get()).isEqualTo(1);
        }
        var idle = new ControlledProvider(false, false);
        try (var traces = new TraceProbe();
                var context = start(idle, traces)) {
            await().during(Duration.ofMillis(200))
                    .atMost(Duration.ofSeconds(3))
                    .untilAsserted(
                            () -> {
                                assertCleaned(context, id, original);
                                assertThat(idle.calls.get()).isZero();
                            });
        }
    }

    private void awaitReady(ConfigurableApplicationContext context, UUID id) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            assertThat(embeddingState(context, id)).isEqualTo("COMPLETED");
                            assertThat(timer(context)).isEqualTo(1);
                            assertThat(facts(context, id)).containsExactlyElementsOf(STAGES);
                        });
        clock.advance(Duration.ofSeconds(5)); // Expire a possible pre-publication metric snapshot.
        assertThat(active(context)).isEqualTo(1);
    }

    private static void assertLocationsAndIdentity(
            ConfigurableApplicationContext context, UUID id, Format format) {
        assertThat(
                        jdbc(context)
                                .sql(
                                        "SELECT count(*) = count(DISTINCT passage_id) FROM document_passages WHERE document_id = :id")
                                .param("id", id)
                                .query(Boolean.class)
                                .single())
                .isTrue();
        assertThat(
                        jdbc(context)
                                .sql(
                                        "SELECT count(*) FROM document_passages WHERE document_id = :id AND start_line < 1")
                                .param("id", id)
                                .query(Long.class)
                                .single())
                .isZero();
        var pages =
                jdbc(context)
                        .sql(
                                "SELECT DISTINCT page_number FROM document_passages WHERE document_id = :id AND page_number IS NOT NULL ORDER BY page_number")
                        .param("id", id)
                        .query(Integer.class)
                        .list();
        if (format == Format.PDF) assertThat(pages).containsExactly(1, 3);
        else assertThat(pages).isEmpty();
    }

    private static void assertCorrelation(
            ConfigurableApplicationContext context, UUID id, UUID correlation) {
        UUID previous = null;
        for (var stage : STAGES) {
            var row =
                    jdbc(context)
                            .sql(
                                    "SELECT k.event_id, e.correlation_id, e.causation_id FROM event_keys k JOIN domain_events e USING(event_id) WHERE k.document_id = :id AND k.event_type = :type")
                            .param("id", id)
                            .param("type", stage)
                            .query(
                                    (rs, n) ->
                                            new UUID[] {
                                                rs.getObject("event_id", UUID.class),
                                                rs.getObject("correlation_id", UUID.class),
                                                rs.getObject("causation_id", UUID.class)
                                            })
                            .single();
            assertThat(row[1]).isEqualTo(correlation);
            assertThat(row[2]).isEqualTo(previous);
            previous = row[0];
        }
    }

    private static void assertStageTraces(
            TraceProbe traces, ConfigurableApplicationContext context, UUID id, UUID correlation) {
        var attempts =
                traces.exporter.getFinishedSpanItems().stream()
                        .filter(
                                span ->
                                        span.getName().equals("event.delivery")
                                                && id.toString()
                                                        .equals(
                                                                span.getAttributes()
                                                                        .get(
                                                                                AttributeKey
                                                                                        .stringKey(
                                                                                                "document_id"))))
                        .toList();
        assertThat(attempts).hasSize(4);
        for (var span : attempts) {
            assertThat(span.getAttributes().get(AttributeKey.stringKey("correlation_id")))
                    .isEqualTo(correlation.toString());
            assertThat(span.getAttributes().get(AttributeKey.stringKey("outcome")))
                    .isEqualTo("acknowledged");
            var event =
                    UUID.fromString(span.getAttributes().get(AttributeKey.stringKey("event_id")));
            var parent =
                    jdbc(context)
                            .sql("SELECT trace_parent FROM domain_events WHERE event_id = :event")
                            .param("event", event)
                            .query((rs, n) -> Optional.ofNullable(rs.getString(1)))
                            .single();
            if (parent.isPresent()) {
                assertThat(span.getLinks()).hasSize(1);
                assertThat(span.getLinks().getFirst().getSpanContext().getTraceId())
                        .isEqualTo(parent.orElseThrow().substring(3, 35));
                assertThat(span.getLinks().getFirst().getSpanContext().getSpanId())
                        .isEqualTo(parent.orElseThrow().substring(36, 52));
            }
        }
        assertThat(
                        jdbc(context)
                                .sql(
                                        "SELECT count(*) FROM domain_events e JOIN event_keys k USING(event_id) WHERE k.document_id = :id AND k.event_type <> 'DocumentAccepted' AND e.trace_parent IS NOT NULL")
                                .param("id", id)
                                .query(Long.class)
                                .single())
                .isEqualTo(4);
    }

    private static void assertCleaned(
            ConfigurableApplicationContext context, UUID id, Path original) {
        for (var table :
                List.of(
                        "documents",
                        "document_extractions",
                        "document_text_lines",
                        "document_index_versions",
                        "document_passages",
                        "document_embeddings"))
            assertThat(rows(context, table, id)).as(table).isZero();
        assertThat(original).doesNotExist();
        assertThat(active(context)).isZero();
        assertThat(timer(context)).isZero();
        assertThat(facts(context, id)).containsExactly("DocumentDeletionRequested");
        assertThat(
                        jdbc(context)
                                .sql(
                                        "SELECT count(*) FROM event_keys WHERE document_id = :id AND event_type = 'IndexPublished'")
                                .param("id", id)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    private static List<String> facts(ConfigurableApplicationContext context, UUID id) {
        return jdbc(context)
                .sql(
                        "SELECT k.event_type FROM event_keys k JOIN domain_events e USING(event_id) WHERE k.document_id = :id ORDER BY CASE k.event_type WHEN 'DocumentAccepted' THEN 1 WHEN 'TextExtracted' THEN 2 WHEN 'ReindexRequested' THEN 3 WHEN 'PassagesPrepared' THEN 4 ELSE 5 END")
                .param("id", id)
                .query(String.class)
                .list();
    }

    private static long rows(ConfigurableApplicationContext context, String table, UUID id) {
        return jdbc(context)
                .sql("SELECT count(*) FROM " + table + " WHERE document_id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private static String embeddingState(ConfigurableApplicationContext context, UUID id) {
        return jdbc(context)
                .sql(
                        "SELECT d.state FROM event_deliveries d JOIN event_keys k USING(event_id) WHERE k.document_id = :id AND d.handler_id = 'documents.embed-passages.v1'")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElse("UNSCHEDULED");
    }

    private static int attempts(ConfigurableApplicationContext context, UUID id) {
        return jdbc(context)
                .sql(
                        "SELECT d.attempts FROM event_deliveries d JOIN event_keys k USING(event_id) WHERE k.document_id = :id AND d.handler_id = 'documents.embed-passages.v1'")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private static MeterRegistry meters(ConfigurableApplicationContext context) {
        return context.getBean(MeterRegistry.class);
    }

    private static double active(ConfigurableApplicationContext context) {
        return meters(context).get("secondbrain.index.active").gauge().value();
    }

    private static long timer(ConfigurableApplicationContext context) {
        return meters(context).get("secondbrain.index.ready.duration").timer().count();
    }

    private static JdbcClient jdbc(ConfigurableApplicationContext context) {
        return context.getBean(JdbcClient.class);
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    private static UUID upload(
            ConfigurableApplicationContext context,
            HttpClient client,
            Format format,
            UUID correlation)
            throws Exception {
        var bytes =
                format == Format.PDF
                        ? PdfFixtures.text(
                                "Private synthetic source page one.",
                                "",
                                "Private synthetic source page three.")
                        : IntStream.rangeClosed(1, 80)
                                .mapToObj(
                                        i ->
                                                "Private synthetic source line "
                                                        + i
                                                        + ": unique study material with café and Unicode 📚.\n")
                                .collect(java.util.stream.Collectors.joining())
                                .getBytes(StandardCharsets.UTF_8);
        var body = new ByteArrayOutputStream();
        body.writeBytes(
                ("--workflow-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"private-workflow."
                                + format.extension
                                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(bytes);
        body.writeBytes("\r\n--workflow-boundary--\r\n".getBytes(StandardCharsets.UTF_8));
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/documents"))
                        .timeout(Duration.ofSeconds(10))
                        .header("X-Correlation-ID", correlation.toString())
                        .header("Content-Type", "multipart/form-data; boundary=workflow-boundary")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.headers().firstValue("X-Correlation-ID"))
                .contains(correlation.toString());
        var json = JsonMapper.builder().build().readTree(response.body());
        assertThat(json.get("state").asString()).isEqualTo("QUEUED");
        return UUID.fromString(json.get("documentId").asString());
    }

    private ConfigurableApplicationContext start(ControlledProvider inference, TraceProbe traces) {
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.SERVLET)
                .initializers(
                        context -> {
                            context.getBeanFactory().registerSingleton("workflowClock", clock);
                            context.getBeanFactory()
                                    .registerSingleton("workflowEmbeddings", inference);
                            context.getBeanFactory()
                                    .registerSingleton("workflowTracer", traces.tracer);
                        })
                .run(
                        "--spring.config.import=",
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--secondbrain.telemetry.otlp-endpoint=",
                        "--management.tracing.sampling.probability=1.0",
                        "--secondbrain.embeddings.enabled=true",
                        "--secondbrain.embeddings.batch-size=1",
                        "--secondbrain.events.concurrency=1",
                        "--secondbrain.events.claim-batch-size=1",
                        "--secondbrain.events.poll-interval=50ms",
                        "--secondbrain.events.polling-enabled=true",
                        "--secondbrain.events.max-attempts=2",
                        "--secondbrain.documents.cleanup.enabled=false",
                        "--secondbrain.documents.indexing.reconcile-enabled=false",
                        "--secondbrain.documents.extraction.reconcile-enabled=false",
                        "--secondbrain.documents.deletion.reconcile-enabled=false",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--secondbrain.documents.storage-directory=" + storage);
    }

    enum Format {
        TXT("txt"),
        MARKDOWN("md"),
        PDF("pdf");
        private final String extension;

        Format(String extension) {
            this.extension = extension;
        }
    }

    private final class ControlledProvider implements EmbeddingProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<String> inputs = new CopyOnWriteArrayList<>();
        private final boolean quotaSecond;

        ControlledProvider(boolean holdFirst, boolean quotaSecond) {
            if (!holdFirst) release.countDown();
            this.quotaSecond = quotaSecond;
        }

        @Override
        public EmbeddingModel model() {
            return new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
        }

        @Override
        public List<EmbeddingVector> embed(List<String> inputs) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            int call = calls.incrementAndGet();
            this.inputs.addAll(inputs);
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Synthetic inference gate timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Synthetic inference interrupted");
            }
            if (quotaSecond && call == 2) {
                var failure = mock(EmbeddingFailure.class);
                when(failure.reason()).thenReturn(EmbeddingFailure.Reason.QUOTA_EXHAUSTED);
                when(failure.retryable()).thenReturn(true);
                when(failure.retryAt())
                        .thenReturn(Optional.of(clock.instant().plus(Duration.ofHours(1))));
                throw failure;
            }
            return inputs.stream()
                    .map(
                            ignored -> {
                                float[] values = new float[1024];
                                values[0] = 1;
                                return new EmbeddingVector(model(), values);
                            })
                    .toList();
        }
    }

    private static final class TraceProbe implements AutoCloseable {
        private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
        private final SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .setSampler(Sampler.alwaysOn())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        private final OtelTracer tracer;

        private TraceProbe() {
            var listener = new Slf4JEventListener();
            tracer =
                    new OtelTracer(
                            provider.get("workflow"),
                            new OtelCurrentTraceContext(),
                            listener::onEvent);
        }

        @Override
        public void close() {
            provider.close();
        }
    }
}
