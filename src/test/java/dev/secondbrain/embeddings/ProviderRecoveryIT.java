package dev.secondbrain.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.documents.DocumentAcceptanceService;
import dev.secondbrain.documents.DocumentProcessingService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real adapter HTTP + durable workers + PostgreSQL; all inputs, tokens and provider replies are
 * synthetic.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ProviderRecoveryIT {
    private static final String PRIVATE = "recovery-private-sentinel";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir private Path storage;
    private final TestClock clock = new TestClock();
    private final Stub stub = new Stub();

    @BeforeEach
    void startOnlyTestHttp() throws IOException {
        stub.start();
    }

    @AfterEach
    void cleanOnlyIsolatedState() throws Exception {
        stub.stop();
        jdbc().sql("TRUNCATE documents, event_keys CASCADE").update();
    }

    @ParameterizedTest
    @CsvSource({
        "401,0,ACCESS_DENIED,false", "403,5035,MODEL_UNAVAILABLE,false",
        "400,0,INVALID_INPUT,false", "200,0,INVALID_RESPONSE,false",
        "429,3040,RATE_LIMITED,true", "429,3036,QUOTA_EXHAUSTED,true",
        "503,0,UNAVAILABLE,true", "408,3007,TIMED_OUT,true"
    })
    void preservesBudgetsStagingAndDiagnosticsAcrossHttpFailureRestartAndManualRecovery(
            int status,
            int code,
            EmbeddingFailure.Reason reason,
            boolean retryable,
            CapturedOutput output)
            throws Exception {
        stub.status = status;
        stub.code = code;
        UUID id;
        UUID delivery;
        List<UUID> passages;
        String traceParent;
        var spans = new ArrayList<SpanData>();
        try (var first = start()) {
            id = upload(first, "ALPHA");
            waitFor(id, retryable ? "PENDING" : "FAILED", 1);
            idle(first);
            delivery = delivery(id);
            passages = passageIds(id);
            assertThat(stub.alphaCalls)
                    .hasValue(2); // First batch saved, then exactly one failed request.
            assertThat(vectorCount(id)).isEqualTo(1);
            assertThat(active(id)).isFalse();
            assertThat(
                            jdbc().sql(
                                            "SELECT trace_parent FROM domain_events e JOIN event_deliveries d USING(event_id) WHERE d.delivery_id = :id")
                                    .param("id", delivery)
                                    .query(String.class)
                                    .single())
                    .isNotNull();
            traceParent =
                    jdbc().sql(
                                    "SELECT trace_parent FROM domain_events e JOIN event_deliveries d USING(event_id) WHERE d.delivery_id = :id")
                            .param("id", delivery)
                            .query(String.class)
                            .single();
            assertThat(
                            first.getBean(MeterRegistry.class)
                                    .get("secondbrain.embedding.requests")
                                    .tag(
                                            "outcome",
                                            reason.name().toLowerCase(java.util.Locale.ROOT))
                                    .counter()
                                    .count())
                    .isEqualTo(1);
            if (reason == EmbeddingFailure.Reason.QUOTA_EXHAUSTED) {
                // One worker must remain free to process another document while ALPHA waits for
                // reset.
                var other = upload(first, "BETA");
                await().atMost(Duration.ofSeconds(15))
                        .untilAsserted(() -> assertThat(active(other)).isTrue());
                idle(first);
                assertThat(stub.alphaCalls).hasValue(2);
                assertThat(backlog(first, "delayed")).isEqualTo(1);
            }
            spans.addAll(spans(first));
        }
        try (var restarted = start()) {
            await().during(Duration.ofMillis(200))
                    .atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(stub.alphaCalls).hasValue(2));
            assertThat(attempts(delivery)).isEqualTo(1);
            assertThat(vectorCount(id)).isEqualTo(1);
            if (retryable) {
                assertThat(backlog(restarted, "delayed")).isEqualTo(1);
                var eligible =
                        jdbc().sql(
                                        "SELECT eligible_at FROM event_deliveries WHERE delivery_id = :id")
                                .param("id", delivery)
                                .query(Timestamp.class)
                                .single()
                                .toInstant();
                if (reason != EmbeddingFailure.Reason.TIMED_OUT)
                    assertThat(eligible).isEqualTo(clock.instant().plusSeconds(60));
                else
                    assertThat(eligible)
                            .isAfter(clock.instant())
                            .isBefore(clock.instant().plusSeconds(1));
                clock.advance(Duration.between(clock.instant(), eligible).plusSeconds(1));
                waitFor(id, "FAILED", 2);
                idle(restarted);
                assertThat(stub.alphaCalls).hasValue(3);
                assertThat(vectorCount(id)).isEqualTo(1);
            }
            var processing = restarted.getBean(DocumentProcessingService.class);
            var failed = processing.read(Set.of(id)).get(id);
            assertThat(failed.state()).isEqualTo(DocumentProcessingService.State.FAILED);
            assertThat(failed.retry()).isPresent();
            // Move past a simulated reset; exhaustion itself never starts another automatic cycle.
            clock.advance(Duration.ofDays(1));
            assertThat(backlog(restarted, "failed")).isEqualTo(1);
            stub.healthy.set(true);
            var selection = failed.retry().orElseThrow();
            assertThat(
                            processing.retry(
                                    id,
                                    selection.deliveryId(),
                                    selection.cycle(),
                                    UUID.randomUUID()))
                    .isTrue();
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(active(id)).isTrue());
            idle(restarted);
            assertThat(processing.read(Set.of(id)).get(id).state())
                    .isEqualTo(DocumentProcessingService.State.READY);
            assertThat(delivery(id)).isEqualTo(delivery);
            assertThat(attempts(delivery)).isEqualTo(retryable ? 3 : 2);
            assertThat(passageIds(id)).containsExactlyElementsOf(passages);
            assertThat(vectorCount(id)).isEqualTo(passages.size());
            assertThat(stub.alphaCalls).hasValue(passages.size() + (retryable ? 2 : 1));
            assertThat(
                            jdbc().sql(
                                            "SELECT count(*) FROM event_keys WHERE document_id = :id AND event_type = 'IndexPublished'")
                                    .param("id", id)
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(1);
            assertThat(
                            jdbc().sql(
                                            "SELECT count(*) FROM event_delivery_retries WHERE delivery_id = :id")
                                    .param("id", delivery)
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(1);
            spans.addAll(spans(restarted));
            var attempts =
                    spans.stream()
                            .filter(
                                    s ->
                                            s.getName().equals("event.delivery")
                                                    && delivery.toString()
                                                            .equals(
                                                                    s.getAttributes()
                                                                            .get(
                                                                                    AttributeKey
                                                                                            .stringKey(
                                                                                                    "delivery_id"))))
                            .toList();
            assertThat(attempts).hasSize(retryable ? 3 : 2);
            assertThat(attempts)
                    .allSatisfy(
                            span -> {
                                assertThat(span.getLinks()).hasSize(1);
                                assertThat(span.getLinks().getFirst().getSpanContext().getTraceId())
                                        .isEqualTo(traceParent.substring(3, 35));
                            });
            assertThat(attempts.getLast().getAttributes().get(AttributeKey.stringKey("outcome")))
                    .isEqualTo("acknowledged");
            assertThat(restarted.getBean(MeterRegistry.class).getMeters())
                    .allSatisfy(
                            meter ->
                                    assertThat(meter.getId().getTags().toString())
                                            .doesNotContain(
                                                    PRIVATE, id.toString(), delivery.toString()));
        }
        assertThat(output).doesNotContain(PRIVATE, "synthetic-recovery-token");
        assertThat(spans)
                .allSatisfy(
                        s ->
                                assertThat(s.getAttributes().toString() + s.getEvents())
                                        .doesNotContain(PRIVATE, "synthetic-recovery-token"));
    }

    @Test
    void transientHttpFailureRecoversAutomaticallyWithoutOpeningANewCycle() throws Exception {
        try (var context = start()) {
            var id = upload(context, "ALPHA");
            waitFor(id, "PENDING", 1);
            idle(context);
            stub.healthy.set(true);
            clock.advance(Duration.ofSeconds(61));
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(active(id)).isTrue());
            idle(context);
            assertThat(attempts(delivery(id))).isEqualTo(2);
            assertThat(stub.alphaCalls).hasValue(passageIds(id).size() + 1);
            assertThat(
                            jdbc().sql(
                                            "SELECT retry_cycle FROM event_deliveries WHERE delivery_id = :id")
                                    .param("id", delivery(id))
                                    .query(Integer.class)
                                    .single())
                    .isZero();
            assertThat(
                            jdbc().sql("SELECT count(*) FROM event_delivery_retries")
                                    .query(Integer.class)
                                    .single())
                    .isZero();
        }
    }

    @Test
    void shutdownCancelsHttpAndRestartResumesWithoutPublishingTheLateResponse(CapturedOutput output)
            throws Exception {
        stub.block.set(true);
        UUID id;
        var first = start();
        try {
            try {
                id = upload(first, "ALPHA");
                assertThat(stub.entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(vectorCount(id)).isEqualTo(1);
                assertThat(active(id)).isFalse();
            } finally {
                first.close();
            }
            // The real worker/HTTP call has stopped; the withheld response cannot publish.
            assertThat(active(id)).isFalse();
            assertThat(vectorCount(id)).isEqualTo(1);
        } finally {
            stub.release.countDown();
        }
        stub.healthy.set(true);
        try (var restarted = start()) {
            // Either graceful cancellation recorded failure, or its old lease needs expiry
            // recovery.
            clock.advance(Duration.ofSeconds(12));
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                            () -> {
                                var state = state(id);
                                assertThat(state).isIn("PENDING", "COMPLETED");
                            });
            clock.advance(Duration.ofSeconds(1));
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(active(id)).isTrue());
            idle(restarted);
            assertThat(attempts(delivery(id))).isEqualTo(2);
            assertThat(vectorCount(id)).isEqualTo(passageIds(id).size());
            assertThat(
                            jdbc().sql(
                                            "SELECT count(*) FROM event_keys WHERE document_id = :id AND event_type = 'IndexPublished'")
                                    .param("id", id)
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(1);
        }
        assertThat(output).doesNotContain(PRIVATE, "synthetic-recovery-token");
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(
                        SecondBrainApplication.class, FixtureConfiguration.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .initializers(
                        c -> {
                            c.getBeanFactory().registerSingleton("recoveryClock", clock);
                            c.getBeanFactory().registerSingleton("recoveryStub", stub);
                            // Register the type before regular configuration conditions run.
                            // A later @Bean method can otherwise coexist with the default provider.
                            var provider =
                                    new RootBeanDefinition(CloudflareEmbeddingProvider.class);
                            provider.setInstanceSupplier(
                                    () ->
                                            testProvider(
                                                    stub,
                                                    clock,
                                                    c.getBean(MeterRegistry.class),
                                                    c.getBean(ObservationRegistry.class)));
                            provider.setDestroyMethodName("close");
                            ((BeanDefinitionRegistry) c.getBeanFactory())
                                    .registerBeanDefinition("testEmbeddingProvider", provider);
                        })
                .run(
                        "--spring.config.import=",
                        "--secondbrain.telemetry.otlp-endpoint=",
                        "--spring.main.web-application-type=none",
                        "--secondbrain.events.enabled=true",
                        "--secondbrain.events.polling-enabled=true",
                        "--secondbrain.questions.enabled=false",
                        "--secondbrain.documents.extraction.enabled=true",
                        "--secondbrain.documents.indexing.enabled=true",
                        "--secondbrain.documents.indexing.passages.enabled=true",
                        "--management.otlp.metrics.export.enabled=false",
                        "--management.tracing.export.otlp.enabled=false",
                        "--management.logging.export.otlp.enabled=false",
                        "--secondbrain.embeddings.enabled=true",
                        "--secondbrain.embeddings.batch-size=1",
                        "--secondbrain.events.concurrency=1",
                        "--secondbrain.events.claim-batch-size=1",
                        "--secondbrain.events.poll-interval=50ms",
                        "--secondbrain.events.lease-duration=3s",
                        "--secondbrain.events.execution-timeout=10s",
                        "--secondbrain.events.transaction-timeout=1s",
                        "--secondbrain.events.max-attempts=2",
                        "--secondbrain.events.initial-backoff=100ms",
                        "--secondbrain.events.max-backoff=100ms",
                        "--secondbrain.documents.cleanup.enabled=false",
                        "--secondbrain.documents.indexing.reconcile-enabled=false",
                        "--secondbrain.documents.extraction.reconcile-enabled=false",
                        "--secondbrain.documents.deletion.reconcile-enabled=false",
                        "--management.tracing.sampling.probability=1",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--secondbrain.documents.storage-directory=" + storage);
    }

    private UUID upload(ConfigurableApplicationContext context, String marker) throws Exception {
        var data =
                (marker + " " + PRIVATE + " study notes.\n")
                        .repeat(100)
                        .getBytes(StandardCharsets.UTF_8);
        var tracer = context.getBean(Tracer.class);
        var span = tracer.nextSpan().name("upload").start();
        var scope = tracer.withSpan(span);
        try (scope) {
            return context.getBean(DocumentAcceptanceService.class)
                    .accept(
                            PRIVATE + ".md",
                            data.length,
                            new ByteArrayInputStream(data),
                            UUID.randomUUID())
                    .documentId();
        } finally {
            span.end();
        }
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private UUID delivery(UUID id) {
        return jdbc().sql(
                        "SELECT d.delivery_id FROM event_deliveries d JOIN event_keys k USING(event_id) WHERE k.document_id = :id AND k.event_type = 'PassagesPrepared'")
                .param("id", id)
                .query(UUID.class)
                .single();
    }

    private int attempts(UUID id) {
        return jdbc().sql("SELECT attempts FROM event_deliveries WHERE delivery_id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private String state(UUID id) {
        return jdbc().sql(
                        "SELECT d.state FROM event_deliveries d JOIN event_keys k USING(event_id) WHERE k.document_id = :id AND k.event_type = 'PassagesPrepared'")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElse("UNSCHEDULED");
    }

    private void waitFor(UUID id, String state, int attempts) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            assertThat(state(id)).isEqualTo(state);
                            assertThat(attempts(delivery(id))).isEqualTo(attempts);
                        });
    }

    private long vectorCount(UUID id) {
        return jdbc().sql("SELECT count(*) FROM document_embeddings WHERE document_id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private boolean active(UUID id) {
        return jdbc().sql(
                        "SELECT active_indexing_version IS NOT NULL FROM documents WHERE document_id = :id")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    private List<UUID> passageIds(UUID id) {
        return jdbc().sql(
                        "SELECT passage_id FROM document_passages WHERE document_id = :id ORDER BY sequence_number")
                .param("id", id)
                .query(UUID.class)
                .list();
    }

    private void idle(ConfigurableApplicationContext context) {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(
                                                context.getBean(MeterRegistry.class)
                                                        .get("secondbrain.event.workers.active")
                                                        .gauge()
                                                        .value())
                                        .isZero());
    }

    private double backlog(ConfigurableApplicationContext context, String state) {
        return context.getBean(MeterRegistry.class)
                .get("secondbrain.event.backlog")
                .tag("state", state)
                .gauge()
                .value();
    }

    private List<SpanData> spans(ConfigurableApplicationContext context) {
        context.getBean(SdkTracerProvider.class).forceFlush().join(5, TimeUnit.SECONDS);
        return context.getBean("recoverySpans", InMemorySpanExporter.class).getFinishedSpanItems();
    }

    private static CloudflareEmbeddingProvider testProvider(
            Stub stub, Clock clock, MeterRegistry meters, ObservationRegistry observations) {
        return new CloudflareEmbeddingProvider(
                new CloudflareEmbeddingSettings(
                        "a".repeat(32),
                        "synthetic-recovery-token",
                        new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024),
                        Duration.ofMillis(100),
                        Duration.ofSeconds(2),
                        1),
                clock,
                stub.endpoint(),
                new EmbeddingTelemetry(meters, observations));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean
        SpanExporter recoverySpans() {
            return InMemorySpanExporter.create();
        }
    }

    private static final class TestClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        TestClock() {
            this(new AtomicReference<>(Instant.parse("2030-01-01T23:59:30Z")), ZoneOffset.UTC);
        }

        private TestClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void advance(Duration duration) {
            now.updateAndGet(time -> time.plus(duration));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new TestClock(now, zone);
        }
    }

    static final class Stub {
        private final AtomicInteger alphaCalls = new AtomicInteger();
        private final AtomicBoolean healthy = new AtomicBoolean();
        private final AtomicBoolean block = new AtomicBoolean();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile int status = 503;
        private volatile int code;
        private HttpServer server;
        private ThreadPoolExecutor executor;

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor =
                    new ThreadPoolExecutor(
                            2,
                            2,
                            0,
                            TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(8),
                            Thread.ofPlatform().daemon(true).name("recovery-http-", 0).factory());
            server.setExecutor(executor);
            server.createContext(
                    "/",
                    exchange -> {
                        try (exchange) {
                            var input =
                                    new JsonMapper()
                                            .readTree(exchange.getRequestBody().readAllBytes())
                                            .path("input")
                                            .get(0)
                                            .stringValue();
                            boolean alpha = input.contains("ALPHA");
                            int call = alpha ? alphaCalls.incrementAndGet() : 0;
                            if (alpha && call > 1 && block.get() && !healthy.get()) {
                                entered.countDown();
                                try {
                                    if (!release.await(10, TimeUnit.SECONDS))
                                        throw new IOException("Stub wait expired");
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new IOException("Stub stopped");
                                }
                                // A valid but late model response must not publish after client
                                // cancellation.
                                reply(
                                        exchange,
                                        200,
                                        "{\"data\":["
                                                + CloudflareEmbeddingProviderTest.entry(0, "1")
                                                + "]}");
                                return;
                            }
                            if (alpha && call > 1 && !healthy.get()) {
                                exchange.getResponseHeaders().set("Retry-After", "60");
                                reply(
                                        exchange,
                                        status,
                                        "{\"errors\":[{\"code\":"
                                                + code
                                                + ",\"message\":\""
                                                + PRIVATE
                                                + "\"}]}");
                            } else
                                reply(
                                        exchange,
                                        200,
                                        "{\"data\":["
                                                + CloudflareEmbeddingProviderTest.entry(0, "1")
                                                + "]}");
                        } catch (IOException disconnected) {
                            /* Cancellation can close the client before this synthetic reply. */
                        }
                    });
            server.start();
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings");
        }

        private static void reply(HttpExchange exchange, int status, String body)
                throws IOException {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        void stop() throws InterruptedException {
            release.countDown();
            if (server != null) server.stop(0);
            if (executor != null) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
