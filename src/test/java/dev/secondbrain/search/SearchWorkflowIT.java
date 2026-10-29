package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.documents.DocumentAcceptanceService;
import dev.secondbrain.documents.DocumentDeletionService;
import dev.secondbrain.documents.PdfFixtures;
import dev.secondbrain.embeddings.EmbeddingFailure;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
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
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Real application/poller/indexing/retrieval wiring. Symbolic vectors prove mechanics, not
 * relevance.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class SearchWorkflowIT {
    private static final String ALPHA = "workflow-private-query-sentinel ALPHA";
    private static final String BETA = "workflow-private-query-sentinel BETA";
    private static final String ABSENT = "workflow-private-query-sentinel UNRELATED";
    private static final EmbeddingModel MODEL = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir private Path storage;
    private final Clock clock = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1000));

    @AfterEach
    void clearOnlyTheIsolatedDatabaseAfterContextsAndWorkersClose() {
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
    void retrievesPublishedMultiFormatSourcesWithScopesStableIdentityReuseAndSafeTelemetry(
            Format format, CapturedOutput output) throws Exception {
        var provider = new ControlledProvider();
        try (var traces = new TraceProbe();
                var context = start(provider, traces)) {
            var service = context.getBean(SearchService.class);
            var caller = traces.tracer.nextSpan().name("workflow.request").start();
            var scope = traces.tracer.withSpan(caller);
            try (scope) {
                assertThat(service.search(query(ALPHA, SearchScope.selected(Set.of()))).matches())
                        .isEmpty();
                assertFailure(
                        service,
                        query(ALPHA, SearchScope.all()),
                        SearchFailure.Reason.NO_COMPATIBLE_INDEX);
                assertThat(provider.calls).isEmpty();
                var alpha = upload(context, format, "ALPHA");
                var beta = upload(context, Format.TXT, "BETA");
                awaitReady(context, 2);
                var vectors = vectors(context);
                var jobs = jobs(context);
                int before = provider.calls.size();

                var found = service.search(query(ALPHA, SearchScope.all()));
                int alphaMatches = format == Format.PDF ? 2 : 1;
                assertThat(found.matches())
                        .hasSize(alphaMatches)
                        .allSatisfy(
                                match -> {
                                    assertThat(match.documentId()).isEqualTo(alpha);
                                    assertThat(match.score()).isEqualTo(1);
                                    assertThat(match.indexingVersion()).isEqualTo(1);
                                    assertThat(match.location().startLine()).isPositive();
                                });
                if (format == Format.PDF)
                    assertThat(found.matches())
                            .extracting(match -> match.location().pageNumber().orElseThrow())
                            .containsExactly(1, 3);
                else
                    assertThat(found.matches())
                            .allSatisfy(
                                    match -> assertThat(match.location().pageNumber()).isEmpty());
                assertThat(
                                service.search(query(ALPHA, SearchScope.selected(Set.of(beta))))
                                        .matches())
                        .isEmpty();
                assertThat(
                                service.search(query(BETA, SearchScope.selected(Set.of(beta))))
                                        .matches())
                        .extracting(SearchResult.Match::documentId)
                        .containsExactly(beta);
                assertThat(service.search(query(ABSENT, SearchScope.all())).matches()).isEmpty();
                assertThat(service.search(query(ALPHA, SearchScope.all()))).isEqualTo(found);
                assertThat(service.search(query(ALPHA, SearchScope.selected(Set.of()))).matches())
                        .isEmpty();
                assertFailure(
                        service,
                        query(ALPHA, SearchScope.selected(Set.of(UUID.randomUUID()))),
                        SearchFailure.Reason.NO_COMPATIBLE_INDEX);
                assertThat(provider.calls.subList(before, provider.calls.size()))
                        .containsExactly(
                                List.of(ALPHA),
                                List.of(ALPHA),
                                List.of(BETA),
                                List.of(ABSENT),
                                List.of(ALPHA));
                assertThat(vectors(context)).isEqualTo(vectors);
                assertThat(jobs(context)).isEqualTo(jobs); // Queries never create delivery work.
                var meters = context.getBean(MeterRegistry.class);
                assertThat(meters.get("secondbrain.search.results").summary().count()).isEqualTo(7);
                assertThat(meters.get("secondbrain.search.results").summary().totalAmount())
                        .isEqualTo(alphaMatches * 2 + 1);
                assertThat(
                                meters.get("secondbrain.search.requests")
                                        .tag("outcome", "success")
                                        .counter()
                                        .count())
                        .isEqualTo(3);
                assertThat(
                                meters.get("secondbrain.search.requests")
                                        .tag("outcome", "no_results")
                                        .counter()
                                        .count())
                        .isEqualTo(2);

                assertThat(
                                context.getBean(DocumentDeletionService.class)
                                        .request(alpha, UUID.randomUUID()))
                        .isTrue();
                assertFailure(
                        service,
                        query(ALPHA, SearchScope.selected(Set.of(alpha))),
                        SearchFailure.Reason.NO_COMPATIBLE_INDEX);
                assertThat(provider.calls.size()).isEqualTo(before + 5);
                assertThat(service.search(query(ALPHA, SearchScope.all())).matches()).isEmpty();
                assertThat(provider.calls.size()).isEqualTo(before + 6);
                assertThat(traces.tracer.currentSpan().context().spanId())
                        .isEqualTo(caller.context().spanId());
            } finally {
                caller.end();
            }
            var searches =
                    traces.exporter.getFinishedSpanItems().stream()
                            .filter(span -> span.getName().equals("search.retrieval"))
                            .toList();
            assertThat(searches)
                    .hasSize(11)
                    .allSatisfy(
                            span -> {
                                assertThat(span.getParentSpanId())
                                        .isEqualTo(caller.context().spanId());
                                assertThat(span.getTraceId()).isEqualTo(caller.context().traceId());
                                assertThat(
                                                span.getAttributes()
                                                        .get(AttributeKey.stringKey("outcome")))
                                        .isNotBlank();
                                assertThat(span.getAttributes().toString())
                                        .doesNotContain("workflow-private");
                                assertThat(span.getEvents()).isEmpty();
                            });
            for (var meter : context.getBean(MeterRegistry.class).getMeters())
                if (meter.getId().getName().startsWith("secondbrain.search."))
                    assertThat(meter.getId().getTags())
                            .allSatisfy(tag -> assertThat(tag.getKey()).isIn("outcome", "error"));
            assertThat(output.getAll())
                    .contains("Search retrieval finished")
                    .doesNotContain(
                            "workflow-private-query-sentinel",
                            "workflow-private-document-sentinel");
        }
    }

    @Test
    void persistedIndexesSurviveRestartAndSearchingDoesNotReembedOrCreateJobs() throws Exception {
        List<SearchResult.Match> original;
        List<String> vectors;
        List<String> jobs;
        try (var traces = new TraceProbe();
                var context = start(new ControlledProvider(), traces)) {
            upload(context, Format.MARKDOWN, "ALPHA");
            upload(context, Format.TXT, "BETA");
            awaitReady(context, 2);
            original =
                    context.getBean(SearchService.class)
                            .search(query(ALPHA, SearchScope.all()))
                            .matches();
            assertThat(original).hasSize(1);
            vectors = vectors(context);
            jobs = jobs(context);
        }
        var restarted = new ControlledProvider();
        try (var traces = new TraceProbe();
                var context = start(restarted, traces)) {
            await().during(Duration.ofMillis(200))
                    .atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(restarted.calls).isEmpty());
            assertThat(
                            context.getBean(MeterRegistry.class)
                                    .get("secondbrain.index.active")
                                    .gauge()
                                    .value())
                    .isEqualTo(2);
            assertThat(
                            context.getBean(MeterRegistry.class)
                                    .get("secondbrain.index.ready.duration")
                                    .timer()
                                    .count())
                    .isZero();
            assertThat(
                            context.getBean(SearchService.class)
                                    .search(query(ALPHA, SearchScope.all()))
                                    .matches())
                    .isEqualTo(original);
            assertThat(restarted.calls).containsExactly(List.of(ALPHA));
            assertThat(vectors(context)).isEqualTo(vectors);
            assertThat(jobs(context)).isEqualTo(jobs);
            assertThat(
                            context.getBean(MeterRegistry.class)
                                    .get("secondbrain.search.requests")
                                    .tag("outcome", "success")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = EmbeddingFailure.Reason.class,
            names = {"TIMED_OUT", "RATE_LIMITED", "QUOTA_EXHAUSTED"})
    void interactiveFailuresStayDistinctFromNoEvidenceAndDoNotRetryOrQueueWork(
            EmbeddingFailure.Reason reason, CapturedOutput output) throws Exception {
        var provider = new ControlledProvider();
        try (var traces = new TraceProbe();
                var context = start(provider, traces)) {
            upload(context, Format.TXT, "ALPHA");
            awaitReady(context, 1);
            int before = provider.calls.size();
            var vectors = vectors(context);
            var jobs = jobs(context);
            provider.queryFailure = reason;
            var expected = SearchFailure.Reason.valueOf(reason.name());
            var service = context.getBean(SearchService.class);
            assertFailure(service, query(ALPHA, SearchScope.all()), expected);
            await().during(Duration.ofMillis(200))
                    .atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(provider.calls.size()).isEqualTo(before + 1));
            assertThat(vectors(context)).isEqualTo(vectors);
            assertThat(jobs(context)).isEqualTo(jobs);
            var meters = context.getBean(MeterRegistry.class);
            assertThat(
                            meters.get("secondbrain.search.requests")
                                    .tag("outcome", reason.name().toLowerCase(Locale.ROOT))
                                    .counter()
                                    .count())
                    .isEqualTo(1);
            assertThat(meters.get("secondbrain.search.results").summary().count()).isZero();
            provider.queryFailure = null; // A new explicit user request may try again.
            assertThat(service.search(query(ALPHA, SearchScope.all())).matches()).hasSize(1);
            assertThat(provider.calls.size()).isEqualTo(before + 2);
            assertThat(meters.get("secondbrain.search.results").summary().count()).isEqualTo(1);
            assertThat(vectors(context)).isEqualTo(vectors);
            assertThat(jobs(context)).isEqualTo(jobs);
            assertThat(output.getAll())
                    .doesNotContain(
                            "workflow-private-query-sentinel",
                            "workflow-private-provider-sentinel");
        }
    }

    private void awaitReady(ConfigurableApplicationContext context, int documents) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            assertThat(
                                            jdbc(context)
                                                    .sql(
                                                            "SELECT count(*) FROM documents WHERE active_indexing_version IS NOT NULL AND deleted_at IS NULL")
                                                    .query(Integer.class)
                                                    .single())
                                    .isEqualTo(documents);
                            assertThat(
                                            jdbc(context)
                                                    .sql(
                                                            "SELECT count(*) FROM event_deliveries WHERE state NOT IN ('COMPLETED', 'SKIPPED')")
                                                    .query(Integer.class)
                                                    .single())
                                    .isZero();
                        });
    }

    private static UUID upload(ConfigurableApplicationContext context, Format format, String marker)
            throws Exception {
        byte[] bytes =
                format == Format.PDF
                        ? PdfFixtures.text(
                                marker + " synthetic source page one.",
                                "",
                                marker + " synthetic source page three.")
                        : ((format == Format.MARKDOWN ? "# " : "")
                                        + marker
                                        + " synthetic source.\n")
                                .getBytes(StandardCharsets.UTF_8);
        try (var input = new ByteArrayInputStream(bytes)) {
            return context.getBean(DocumentAcceptanceService.class)
                    .accept(
                            "workflow-private-document-sentinel-"
                                    + marker.toLowerCase(Locale.ROOT)
                                    + "."
                                    + format.extension,
                            bytes.length,
                            input,
                            UUID.randomUUID())
                    .documentId();
        }
    }

    private static SearchQuery query(String text, SearchScope scope) {
        return new SearchQuery(text, scope);
    }

    private static void assertFailure(
            SearchService service, SearchQuery query, SearchFailure.Reason reason) {
        assertThatThrownBy(() -> service.search(query))
                .isInstanceOfSatisfying(
                        SearchFailure.class,
                        failure -> {
                            assertThat(failure.reason()).isEqualTo(reason);
                            assertThat(failure.getCause()).isNull();
                            assertThat(failure.getMessage()).doesNotContain("workflow-private");
                        });
    }

    private static JdbcClient jdbc(ConfigurableApplicationContext context) {
        return context.getBean(JdbcClient.class);
    }

    private static List<String> vectors(ConfigurableApplicationContext context) {
        return jdbc(context)
                .sql(
                        """
                        SELECT passage_id::text || ':' || document_id::text
                            || ':' || indexing_version::text || ':' || model_id
                            || ':' || dimensions::text || ':' || embedding::text
                        FROM document_embeddings
                        ORDER BY passage_id
                        """)
                .query(String.class)
                .list();
    }

    private static List<String> jobs(ConfigurableApplicationContext context) {
        return jdbc(context)
                .sql(
                        """
                        SELECT k.event_id::text || ':' || k.document_id::text
                            || ':' || k.processing_version::text || ':' || k.event_type
                            || ':' || COALESCE(d.delivery_id::text, '')
                            || ':' || COALESCE(d.state, '') || ':' || COALESCE(d.attempts::text, '')
                        FROM event_keys k
                        LEFT JOIN event_deliveries d ON d.event_id = k.event_id
                        ORDER BY k.event_id, d.delivery_id
                        """)
                .query(String.class)
                .list();
    }

    private ConfigurableApplicationContext start(ControlledProvider provider, TraceProbe traces) {
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(
                        context -> {
                            context.getBeanFactory()
                                    .registerSingleton("searchWorkflowProvider", provider);
                            context.getBeanFactory()
                                    .registerSingleton("searchWorkflowClock", clock);
                            context.getBeanFactory()
                                    .registerSingleton("searchWorkflowTracer", traces.tracer);
                        })
                .run(
                        "--spring.config.import=",
                        "--secondbrain.telemetry.otlp-endpoint=",
                        "--secondbrain.embeddings.enabled=true",
                        "--secondbrain.embeddings.batch-size=1",
                        "--secondbrain.search.minimum-score=0.3",
                        "--secondbrain.events.polling-enabled=true",
                        "--secondbrain.events.concurrency=1",
                        "--secondbrain.events.claim-batch-size=1",
                        "--secondbrain.events.poll-interval=50ms",
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

    private static final class ControlledProvider implements EmbeddingProvider {
        private final List<List<String>> calls = new CopyOnWriteArrayList<>();
        private volatile EmbeddingFailure.Reason queryFailure;

        @Override
        public EmbeddingModel model() {
            return MODEL;
        }

        @Override
        public List<EmbeddingVector> embed(List<String> inputs) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.add(List.copyOf(inputs));
            if (inputs.getFirst().startsWith("workflow-private-query-sentinel")
                    && queryFailure != null) {
                var failure = mock(EmbeddingFailure.class);
                when(failure.reason()).thenReturn(queryFailure);
                when(failure.retryAt())
                        .thenReturn(Optional.of(Instant.parse("2030-01-01T00:00:00Z")));
                when(failure.getMessage()).thenReturn("workflow-private-provider-sentinel");
                throw failure;
            }
            return inputs.stream()
                    .map(
                            text -> {
                                var values = new float[1024];
                                if (text.contains("ALPHA")) values[0] = 1;
                                else if (text.contains("BETA")) values[1] = 1;
                                else values[0] = -1;
                                return new EmbeddingVector(MODEL, values);
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
        private final OtelTracer tracer =
                new OtelTracer(
                        provider.get("search-workflow"),
                        new OtelCurrentTraceContext(),
                        event -> {});

        @Override
        public void close() {
            provider.close();
        }
    }
}
