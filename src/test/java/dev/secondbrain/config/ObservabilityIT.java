package dev.secondbrain.config;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;
import dev.secondbrain.events.EventPublisher;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT {
    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
    private static final EventEnvelope.Type TYPE = new EventEnvelope.Type("TelemetryProbe", 1);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:0.8.7-pg18-bookworm");

    private ConfigurableApplicationContext context;
    private JdbcClient jdbc;

    @BeforeEach
    void prepare() {
        start(false);
        jdbc.sql("CREATE TABLE IF NOT EXISTS telemetry_effects (document_id uuid PRIMARY KEY)")
                .update();
        jdbc.sql("TRUNCATE event_keys, telemetry_effects CASCADE").update();
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void backlogIsRebuiltFromCommittedDatabaseStateAfterRestart() {
        publish(event());
        jdbc.sql("UPDATE event_deliveries SET eligible_at = :time")
                .param("time", Timestamp.from(NOW.minusSeconds(10)))
                .update();
        Assertions.assertThat(gauge("secondbrain.event.backlog", "ready")).isEqualTo(1);
        Assertions.assertThat(gauge("secondbrain.event.backlog.oldest", null)).isEqualTo(10);
        context.close();
        start(false);
        Assertions.assertThat(gauge("secondbrain.event.backlog", "ready")).isEqualTo(1);
        Assertions.assertThat(gauge("secondbrain.event.backlog.oldest", null)).isEqualTo(10);
        jdbc.sql(
                        """
                UPDATE event_deliveries SET state='RUNNING', attempts=1,
                    lease_owner=:owner, lease_token=:token, lease_until=:until, deadline_at=:deadline
                """)
                .param("owner", UUID.randomUUID())
                .param("token", UUID.randomUUID())
                .param("until", Timestamp.from(NOW.minusSeconds(10)))
                .param("deadline", Timestamp.from(NOW.plusSeconds(60)))
                .update();
        context.close();
        start(false);
        Assertions.assertThat(gauge("secondbrain.event.backlog", "ready")).isEqualTo(1);
        Assertions.assertThat(gauge("secondbrain.event.backlog", "running")).isZero();
        Assertions.assertThat(gauge("secondbrain.event.backlog.oldest", null)).isEqualTo(10);
        jdbc.sql(
                        """
                UPDATE event_deliveries SET state='PENDING', attempts=1, eligible_at=:time,
                    lease_owner=NULL, lease_token=NULL, lease_until=NULL, deadline_at=NULL
                """)
                .param("time", Timestamp.from(NOW.plusSeconds(10)))
                .update();
        context.close();
        start(false);
        Assertions.assertThat(gauge("secondbrain.event.backlog", "ready")).isZero();
        Assertions.assertThat(gauge("secondbrain.event.backlog", "delayed")).isEqualTo(1);
        Assertions.assertThat(gauge("secondbrain.event.backlog", "retrying")).isEqualTo(1);
        jdbc.sql("UPDATE event_deliveries SET state='FAILED'").update();
        context.close();
        start(false);
        Assertions.assertThat(gauge("secondbrain.event.backlog", "failed")).isEqualTo(1);
    }

    @Test
    void restartedWorkerLinksItsSpanToTheCommittedProducerAndRestoresCorrelation() {
        var original = event();
        var tracer = context.getBean(Tracer.class);
        var producer = tracer.nextSpan().name("publish").start();
        var scope = tracer.withSpan(producer);
        try (scope) {
            publish(original);
        }
        producer.end();
        String traceId = producer.context().traceId();
        String spanId = producer.context().spanId();
        context.close();
        start(true);
        awaitCompleted();
        var sink = context.getBean(TraceSink.class);
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            context.getBean(SdkTracerProvider.class)
                                    .forceFlush()
                                    .join(1, TimeUnit.SECONDS);
                            var spans =
                                    sink.capture.getFinishedSpanItems().stream()
                                            .filter(span -> span.getName().equals("event.delivery"))
                                            .toList();
                            Assertions.assertThat(spans).hasSize(1);
                            var span = spans.getFirst();
                            Assertions.assertThat(
                                            span.getLinks()
                                                    .getFirst()
                                                    .getSpanContext()
                                                    .getTraceId())
                                    .isEqualTo(traceId);
                            Assertions.assertThat(
                                            span.getLinks().getFirst().getSpanContext().getSpanId())
                                    .isEqualTo(spanId);
                            Assertions.assertThat(
                                            span.getAttributes()
                                                    .get(AttributeKey.stringKey("correlation_id")))
                                    .isEqualTo(original.correlationId().toString());
                        });
        Assertions.assertThat(context.getBean(ProbeHandler.class).seen.get())
                .containsEntry("correlation_id", original.correlationId().toString())
                .containsEntry("causation_id", original.causationId().toString())
                .containsEntry("event_id", original.eventId().toString());
        Assertions.assertThat(counter("completed")).isEqualTo(1);
    }

    @Test
    void failingExporterAndRolledBackEffectsDoNotPreventRetryOrLeakPrivateData(
            CapturedOutput output) {
        context.close();
        start(true);
        var sink = context.getBean(TraceSink.class);
        sink.fail.set(true);
        context.getBean(ProbeHandler.class).failEffect.set(true);
        publish(event());
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            Assertions.assertThat(counter("retry")).isEqualTo(1);
                            Assertions.assertThat(counter("completed")).isZero();
                            Assertions.assertThat(
                                            jdbc.sql("SELECT count(*) FROM telemetry_effects")
                                                    .query(Long.class)
                                                    .single())
                                    .isZero();
                        });
        context.getBean(ProbeHandler.class).failEffect.set(false);
        jdbc.sql("UPDATE event_deliveries SET eligible_at=:now WHERE state='PENDING'")
                .param("now", Timestamp.from(NOW))
                .update();
        awaitCompleted();
        context.getBean(SdkTracerProvider.class).forceFlush().join(5, TimeUnit.SECONDS);
        Assertions.assertThat(counter("completed")).isEqualTo(1);
        Assertions.assertThat(
                        jdbc.sql("SELECT count(*) FROM telemetry_effects")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        Assertions.assertThat(sink.failures.get()).isGreaterThan(0);
        Assertions.assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    private void awaitCompleted() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                Assertions.assertThat(
                                                jdbc.sql("SELECT state FROM event_deliveries")
                                                        .query(String.class)
                                                        .single())
                                        .isEqualTo("COMPLETED"));
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> Assertions.assertThat(counter("completed")).isEqualTo(1));
    }

    private double counter(String outcome) {
        return context.getBean(MeterRegistry.class)
                .get("secondbrain.event.delivery.attempts")
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private double gauge(String name, String state) {
        var search = context.getBean(MeterRegistry.class).get(name);
        return (state == null ? search : search.tag("state", state)).gauge().value();
    }

    private void publish(EventEnvelope event) {
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                .executeWithoutResult(
                        status -> context.getBean(EventPublisher.class).publish(event));
    }

    private EventEnvelope event() {
        return new EventEnvelope(
                UUID.randomUUID(),
                TYPE,
                UUID.randomUUID(),
                1,
                NOW,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                Map.of());
    }

    private void start(boolean polling) {
        context =
                new SpringApplicationBuilder(SecondBrainApplication.class, ProbeConfiguration.class)
                        .web(WebApplicationType.NONE)
                        .run(
                                "--spring.config.import=",
                                "--secondbrain.documents.cleanup.enabled=false",
                                // Probe fixtures reset event tables; document schedulers must not
                                // race them.
                                "--secondbrain.documents.extraction.reconcile-enabled=false",
                                "--secondbrain.documents.deletion.reconcile-enabled=false",
                                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                                "--spring.datasource.username=" + POSTGRES.getUsername(),
                                "--spring.datasource.password=" + POSTGRES.getPassword(),
                                "--secondbrain.events.polling-enabled=" + polling,
                                "--secondbrain.events.poll-interval=50ms",
                                "--secondbrain.events.initial-backoff=100ms",
                                "--management.tracing.sampling.probability=1");
        jdbc = context.getBean(JdbcClient.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        TraceSink traceSink() {
            return new TraceSink();
        }

        @Bean
        SpanExporter probeExporter(TraceSink sink) {
            return new SafeSpanExporter(
                    new SpanExporter() {
                        @Override
                        public CompletableResultCode export(Collection<SpanData> spans) {
                            return sink.export(spans);
                        }

                        @Override
                        public CompletableResultCode flush() {
                            return sink.capture.flush();
                        }

                        @Override
                        public CompletableResultCode shutdown() {
                            return sink.capture.shutdown();
                        }
                    });
        }

        @Bean
        ProbeHandler probeHandler(JdbcClient jdbc) {
            return new ProbeHandler(jdbc);
        }
    }

    static final class TraceSink {
        private final InMemorySpanExporter capture = InMemorySpanExporter.create();
        private final AtomicBoolean fail = new AtomicBoolean();
        private final AtomicInteger failures = new AtomicInteger();

        public CompletableResultCode export(Collection<SpanData> spans) {
            if (fail.get()) {
                failures.incrementAndGet();
                throw new IllegalStateException("sentinel-private-export");
            }
            return capture.export(spans);
        }
    }

    static final class ProbeHandler implements EventHandler {
        private final JdbcClient jdbc;
        private final AtomicBoolean failEffect = new AtomicBoolean();
        private final AtomicReference<Map<String, String>> seen = new AtomicReference<>();

        ProbeHandler(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String id() {
            return "telemetry-probe";
        }

        @Override
        public EventEnvelope.Type eventType() {
            return TYPE;
        }

        @Override
        public Effect prepare(EventEnvelope event) {
            seen.set(MDC.getCopyOfContextMap());
            return () -> {
                jdbc.sql("INSERT INTO telemetry_effects VALUES (:id) ON CONFLICT DO NOTHING")
                        .param("id", event.documentId())
                        .update();
                if (failEffect.get()) {
                    throw new IllegalStateException("sentinel-private-provider");
                }
                return Outcome.COMPLETED;
            };
        }
    }
}
