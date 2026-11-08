package dev.secondbrain.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.documents.PdfFixtures;
import dev.secondbrain.embeddings.EmbeddingFailure;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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

import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Real servlet HTTP, persistence and event-produced indexes; no live model or user library. */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class QuestionHttpIT {
    private static final String QUERY = "http-private-query-sentinel";
    private static final String SOURCE = "http-private-source-sentinel";
    private static final String ANSWER = "http-private-answer-sentinel";
    private static final String NAME = "http-private-name-sentinel";
    private static final String BODY = "http-private-provider-body-token-sentinel";
    private static final EmbeddingModel MODEL = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
    private static final Instant RETRY = Instant.parse("2030-01-02T00:00:00Z");
    private static final Pattern SOURCE_LINK =
            Pattern.compile("href=\"(/documents/[0-9a-f-]{36}/passages/[0-9a-f-]{36})\"");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir private Path storage;

    @AfterEach
    void clearIsolatedRowsAfterHttpContextsAndWorkersClose() {
        JdbcClient.create(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()))
                .sql("TRUNCATE documents, event_keys CASCADE")
                .update();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uploadsIndexesAnswersFollowsSourcesAndDeletesThroughRealHttp(
            boolean pdf, CapturedOutput output) throws Exception {
        var models = new Models();
        try (var context = start(models);
                var client = client()) {
            var origin = origin(context);
            assertThat(get(client, origin, "/").body()).contains("href=\"/questions\"");
            var alpha = upload(client, origin, "ALPHA", pdf);
            var beta = upload(client, origin, "BETA", false);
            ready(context, 2);
            int embeddings = models.queryCalls.get();
            assertThat(get(client, origin, "/questions?q=" + encode(QUERY)).body())
                    .doesNotContain(QUERY);
            assertThat(models.queryCalls).hasValue(embeddings);
            assertThat(models.chatCalls).hasValue(0);
            int events = eventCount(context);
            var response = ask(client, origin, QUERY + " ALPHA", "all", "");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains(
                            ANSWER + " &lt;script&gt;answer()&lt;/script&gt;",
                            SOURCE + " &lt;script&gt;study()&lt;/script&gt;",
                            "Answer using the whole library")
                    .doesNotContain("<script>", ";jsessionid=");
            assertPrivateHeaders(response);
            assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
            var links =
                    SOURCE_LINK
                            .matcher(response.body())
                            .results()
                            .map(result -> result.group(1))
                            .toList();
            assertThat(links).hasSize(pdf ? 2 : 1);
            for (var link : links) {
                var source = get(client, origin, link);
                assertThat(source.statusCode()).isEqualTo(200);
                assertThat(source.body()).contains(SOURCE).doesNotContain(ANSWER, "<script>");
            }
            if (pdf) assertThat(response.body()).contains("Page 1", "Page 3");
            assertThat(models.chatCalls).hasValue(1);
            var absent = ask(client, origin, QUERY + " UNRELATED", "all", "");
            assertThat(absent.statusCode()).isEqualTo(200);
            assertThat(absent.body())
                    .contains("Insufficient information")
                    .doesNotContain(ANSWER, "id=\"answer-title\"");
            var scoped = ask(client, origin, QUERY + " ALPHA", "selected", "&documentId=" + beta);
            assertThat(scoped.body())
                    .contains("Insufficient information", "Selected documents: 1")
                    .doesNotContain(ANSWER);
            int beforeEmpty = models.queryCalls.get();
            var empty = ask(client, origin, QUERY + " ALPHA", "selected", "");
            assertThat(empty.body()).contains("No documents selected");
            assertThat(models.queryCalls).hasValue(beforeEmpty);
            assertThat(models.chatCalls).hasValue(1);
            var paged =
                    post(
                            client,
                            origin,
                            "/questions",
                            "q=&scope=selected&documentId=" + alpha + "&page=0&action=next");
            assertThat(paged.statusCode()).isEqualTo(200);
            assertThat(paged.body()).contains("Page 2", alpha.toString());
            assertThat(models.queryCalls).hasValue(beforeEmpty);
            assertThat(models.chatCalls).hasValue(1);
            assertThat(eventCount(context)).isEqualTo(events);
            assertThat(post(client, origin, "/documents/" + alpha + "/delete", "").statusCode())
                    .isEqualTo(303);
            for (var link : links) {
                var gone = get(client, origin, link);
                assertThat(gone.statusCode()).isEqualTo(404);
                assertThat(gone.body()).doesNotContain(SOURCE, ANSWER, NAME);
            }
            var removed = ask(client, origin, QUERY + " ALPHA", "selected", "&documentId=" + alpha);
            assertThat(removed.body())
                    .contains("No searchable documents")
                    .doesNotContain(ANSWER, "id=\"answer-title\"");
            assertThat(models.chatCalls).hasValue(1);
        }
        assertSafeOutput(output);
    }

    @ParameterizedTest
    @CsvSource({
        "INSUFFICIENT,200,Insufficient information",
        "INVALID_CITATIONS,503,Answer sources could not be verified",
        "INVALID_RESPONSE,503,Unusable provider response",
        "CHAT_QUOTA,429,Free allowance exhausted",
        "SEARCH_QUOTA,429,Free allowance exhausted"
    })
    void rendersRealPipelineAbsenceAndFailuresWithoutLeakingTheCandidateOrProviderBody(
            Mode mode, int status, String message, CapturedOutput output) throws Exception {
        var models = new Models();
        try (var context = start(models);
                var client = client()) {
            var origin = origin(context);
            upload(client, origin, "ALPHA", false);
            ready(context, 1);
            models.mode.set(mode);
            var response = ask(client, origin, QUERY + " ALPHA", "all", "");
            assertThat(response.statusCode()).isEqualTo(status);
            assertThat(response.body())
                    .contains(message, QUERY)
                    .doesNotContain(ANSWER, SOURCE, BODY, "id=\"answer-title\"");
            assertPrivateHeaders(response);
            if (mode == Mode.CHAT_QUOTA || mode == Mode.SEARCH_QUOTA)
                assertThat(response.body()).contains(RETRY.toString(), "(UTC)");
            assertThat(models.chatCalls).hasValue(mode == Mode.SEARCH_QUOTA ? 0 : 1);
        }
        assertSafeOutput(output);
    }

    @Test
    void deletingEvidenceDuringTheHttpQuestionReturnsOnlyChangedSourceFeedback(
            CapturedOutput output) throws Exception {
        var models = new Models();
        try (var context = start(models);
                var client = client()) {
            var origin = origin(context);
            var id = upload(client, origin, "ALPHA", false);
            ready(context, 1);
            models.mode.set(Mode.BLOCKED);
            var request =
                    formRequest(
                            origin,
                            "/questions",
                            "q=" + encode(QUERY + " ALPHA") + "&scope=all&action=ask");
            var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                assertThat(models.arrived.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(post(client, origin, "/documents/" + id + "/delete", "").statusCode())
                        .isEqualTo(303);
                models.release.countDown();
                var response = pending.get(15, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(409);
                assertThat(response.body())
                        .contains("Answer sources changed", QUERY)
                        .doesNotContain(ANSWER, SOURCE, NAME, "id=\"answer-title\"");
                assertPrivateHeaders(response);
            } finally {
                models.release.countDown();
                pending.cancel(true);
            }
        }
        assertSafeOutput(output);
    }

    enum Mode {
        ANSWER,
        INSUFFICIENT,
        INVALID_CITATIONS,
        INVALID_RESPONSE,
        CHAT_QUOTA,
        SEARCH_QUOTA,
        BLOCKED
    }

    private static final class Models implements EmbeddingProvider, ChatProvider {
        private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.ANSWER);
        private final AtomicInteger chatCalls = new AtomicInteger();
        private final AtomicInteger queryCalls = new AtomicInteger();
        private final CountDownLatch arrived = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public EmbeddingModel model() {
            return MODEL;
        }

        @Override
        public List<EmbeddingVector> embed(List<String> inputs) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (inputs.getFirst().startsWith(QUERY)) {
                queryCalls.incrementAndGet();
                if (mode.get() == Mode.SEARCH_QUOTA) {
                    var failure = mock(EmbeddingFailure.class);
                    when(failure.reason()).thenReturn(EmbeddingFailure.Reason.QUOTA_EXHAUSTED);
                    when(failure.retryAt()).thenReturn(Optional.of(RETRY));
                    when(failure.getMessage()).thenReturn(BODY);
                    throw failure;
                }
            }
            return inputs.stream()
                    .map(
                            text -> {
                                var vector = new float[1024];
                                vector[text.contains("ALPHA") ? 0 : text.contains("BETA") ? 1 : 2] =
                                        1;
                                return new EmbeddingVector(MODEL, vector);
                            })
                    .toList();
        }

        @Override
        public Reply generate(Request request) throws InterruptedException {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            chatCalls.incrementAndGet();
            if (mode.get() == Mode.BLOCKED) {
                arrived.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            if (mode.get() == Mode.CHAT_QUOTA)
                throw new ChatFailure(
                        ChatFailure.Reason.QUOTA_EXHAUSTED, RETRY, new RuntimeException(BODY));
            if (mode.get() == Mode.INVALID_RESPONSE)
                throw new ChatFailure(
                        ChatFailure.Reason.INVALID_RESPONSE, null, new RuntimeException(BODY));
            if (mode.get() == Mode.INSUFFICIENT)
                return new Reply(Outcome.INSUFFICIENT_INFORMATION, "", List.of());
            return new Reply(
                    Outcome.ANSWERED,
                    ANSWER + " <script>answer()</script>\nArrayList allows duplicates.",
                    mode.get() == Mode.INVALID_CITATIONS
                            ? List.of(UUID.randomUUID())
                            : request.evidence().stream().map(Passage::passageId).toList());
        }
    }

    private ConfigurableApplicationContext start(Models models) {
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.SERVLET)
                .registerShutdownHook(false)
                .initializers(
                        context -> {
                            context.getBeanFactory()
                                    .registerSingleton("questionHttpModels", models);
                            context.getBeanFactory()
                                    .registerSingleton(
                                            "questionHttpClock",
                                            Clock.tick(Clock.systemUTC(), Duration.ofNanos(1000)));
                        })
                .run(
                        "--spring.config.import=",
                        "--secondbrain.telemetry.otlp-endpoint=",
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--secondbrain.questions.enabled=true",
                        "--secondbrain.embeddings.enabled=true",
                        "--secondbrain.embeddings.batch-size=1",
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

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static URI origin(ConfigurableApplicationContext context) {
        return URI.create(
                "http://127.0.0.1:"
                        + context.getEnvironment()
                                .getRequiredProperty("local.server.port", Integer.class));
    }

    private static HttpResponse<String> get(HttpClient client, URI origin, String path)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(origin.resolve(path))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(
            HttpClient client, URI origin, String path, String body) throws Exception {
        return client.send(formRequest(origin, path, body), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest formRequest(URI origin, String path, String body) {
        return HttpRequest.newBuilder(origin.resolve(path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private static HttpResponse<String> ask(
            HttpClient client, URI origin, String text, String scope, String selected)
            throws Exception {
        return post(
                client,
                origin,
                "/questions",
                "q=" + encode(text) + "&scope=" + scope + selected + "&action=ask");
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static UUID upload(HttpClient client, URI origin, String marker, boolean pdf)
            throws Exception {
        var boundary = "question-http-boundary";
        byte[] data =
                pdf
                        ? PdfFixtures.text(
                                "ALPHA " + SOURCE + " <script>study()</script>",
                                "",
                                "ALPHA " + SOURCE + " page three")
                        : (marker + " " + SOURCE + " <script>study()</script>\n")
                                .getBytes(StandardCharsets.UTF_8);
        var body = new ByteArrayOutputStream();
        body.write(
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                                + NAME
                                + marker
                                + (pdf ? ".pdf" : ".md")
                                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        body.write(data);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request =
                HttpRequest.newBuilder(origin.resolve("/documents"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return UUID.fromString(
                new JsonMapper().readTree(response.body()).path("documentId").stringValue());
    }

    private static void ready(ConfigurableApplicationContext context, int count) {
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(
                                                context.getBean(JdbcClient.class)
                                                        .sql(
                                                                "SELECT count(*) FROM documents WHERE active_indexing_version IS NOT NULL")
                                                        .query(Integer.class)
                                                        .single())
                                        .isEqualTo(count));
    }

    private static int eventCount(ConfigurableApplicationContext context) {
        return context.getBean(JdbcClient.class)
                .sql("SELECT count(*) FROM domain_events")
                .query(Integer.class)
                .single();
    }

    private static void assertPrivateHeaders(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(response.headers().firstValue("X-Correlation-ID").orElseThrow())
                .matches("[0-9a-f-]{36}");
    }

    private static void assertSafeOutput(CapturedOutput output) {
        assertThat(output).doesNotContain(QUERY, SOURCE, ANSWER, NAME, BODY);
    }
}
