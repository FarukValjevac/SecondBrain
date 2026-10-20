package dev.secondbrain.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real loopback HTTP with synthetic credentials/text, never a live Cloudflare request. */
@ExtendWith(OutputCaptureExtension.class)
class CloudflareEmbeddingHttpIT {
    private static final String PRIVATE_TEXT = "private-test-document-sentinel";
    private static final String PRIVATE_RESPONSE = "private-test-provider-sentinel";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2030-01-01T23:59:30Z"), ZoneOffset.UTC);
    private HttpServer server;
    private ThreadPoolExecutor serverThreads;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<HttpHandler> behavior = new AtomicReference<>();

    @BeforeEach
    void startOnlyAnIsolatedLoopbackServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads =
                new ThreadPoolExecutor(
                        2,
                        2,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(8),
                        Thread.ofPlatform().daemon(true).name("embedding-stub-", 0).factory());
        server.setExecutor(serverThreads);
        server.createContext(
                "/",
                exchange -> {
                    calls.incrementAndGet();
                    try (exchange) {
                        behavior.get().handle(exchange);
                    }
                });
        server.start();
    }

    @AfterEach
    void stopOwnedResources() throws InterruptedException {
        if (server != null) server.stop(0);
        if (serverThreads != null) {
            serverThreads.shutdownNow();
            assertThat(serverThreads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void sendsOneExactRequestAndReturnsVectorsInInputOrderWithoutContentLogs(CapturedOutput output)
            throws Exception {
        var received = new AtomicReference<byte[]>();
        var authorization = new AtomicReference<String>();
        var path = new AtomicReference<String>();
        var method = new AtomicReference<String>();
        behavior.set(
                exchange -> {
                    received.set(exchange.getRequestBody().readAllBytes());
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    path.set(exchange.getRequestURI().getPath());
                    method.set(exchange.getRequestMethod());
                    reply(
                            exchange,
                            200,
                            "{\"model\":\"@cf/baai/bge-m3\",\"data\":["
                                    + entry(1, "0.75")
                                    + ","
                                    + entry(0, "0.25")
                                    + "]}");
                });
        try (var provider = provider(Duration.ofSeconds(2))) {
            var vectors = provider.embed(List.of(PRIVATE_TEXT, "Café 📚"));
            assertThat(vectors.getFirst().values()[0]).isEqualTo(0.25f);
            assertThat(vectors.get(1).values()[0]).isEqualTo(0.75f);
        }
        assertThat(calls).hasValue(1);
        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get())
                .isEqualTo("/client/v4/accounts/" + "a".repeat(32) + "/ai/v1/embeddings");
        assertThat(authorization.get()).isEqualTo("Bearer synthetic-test-token");
        var request = new JsonMapper().readTree(received.get());
        assertThat(request.path("input").get(0).stringValue()).isEqualTo(PRIVATE_TEXT);
        assertThat(request.path("input").get(1).stringValue()).isEqualTo("Café 📚");
        assertThat(request.path("model").stringValue()).isEqualTo("@cf/baai/bge-m3");
        assertThat(request.path("encoding_format").stringValue()).isEqualTo("float");
        assertThat(output)
                .doesNotContain(
                        PRIVATE_TEXT, PRIVATE_RESPONSE, "synthetic-test-token", "a".repeat(32));
    }

    @ParameterizedTest
    @CsvSource({
        "401,0,ACCESS_DENIED",
        "403,5035,MODEL_UNAVAILABLE",
        "400,5007,MODEL_UNAVAILABLE",
        "404,3042,MODEL_UNAVAILABLE",
        "413,3006,INVALID_INPUT",
        "429,3040,RATE_LIMITED",
        "429,3036,QUOTA_EXHAUSTED",
        "500,0,UNAVAILABLE",
        "503,0,UNAVAILABLE"
    })
    void returnsSafeFailuresWithNoAutomaticRetryOrFallback(
            int status, int code, EmbeddingFailure.Reason reason, CapturedOutput output)
            throws Exception {
        behavior.set(
                exchange -> {
                    exchange.getResponseHeaders().set("Retry-After", "60");
                    reply(
                            exchange,
                            status,
                            "{\"errors\":[{\"code\":"
                                    + code
                                    + ",\"message\":\""
                                    + PRIVATE_RESPONSE
                                    + "\"}]}");
                });
        try (var provider = provider(Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> provider.embed(List.of(PRIVATE_TEXT)))
                    .isInstanceOfSatisfying(
                            EmbeddingFailure.class,
                            failure -> {
                                assertThat(failure.reason()).isEqualTo(reason);
                                assertThat(failure.getCause()).isNull();
                                assertThat(failure.toString())
                                        .doesNotContain(
                                                PRIVATE_TEXT,
                                                PRIVATE_RESPONSE,
                                                "synthetic-test-token");
                                if (reason == EmbeddingFailure.Reason.RATE_LIMITED
                                        || reason == EmbeddingFailure.Reason.QUOTA_EXHAUSTED
                                        || reason == EmbeddingFailure.Reason.UNAVAILABLE) {
                                    assertThat(failure.retryAt())
                                            .contains(CLOCK.instant().plusSeconds(60));
                                }
                            });
        }
        assertThat(calls).hasValue(1);
        assertThat(output).doesNotContain(PRIVATE_TEXT, PRIVATE_RESPONSE, "synthetic-test-token");
    }

    @Test
    void refusesRedirectsWithoutSendingCredentialsToTheRedirectTarget() throws Exception {
        behavior.set(
                exchange -> {
                    exchange.getResponseHeaders()
                            .set("Location", endpoint().resolve("/redirect-target").toString());
                    reply(exchange, 302, "{}");
                });
        try (var provider = provider(Duration.ofSeconds(2))) {
            assertFailure(provider, EmbeddingFailure.Reason.INVALID_RESPONSE);
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void boundsChunkedResponsesBeforeDecodingTheJson() throws Exception {
        behavior.set(
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        exchange.getResponseBody()
                                .write(
                                        ("{\"data\":["
                                                        + entry(0, "1")
                                                        + "]}"
                                                        + " "
                                                                .repeat(
                                                                        CloudflareEmbeddingProtocol
                                                                                .MAXIMUM_RESPONSE_BYTES))
                                                .getBytes(StandardCharsets.UTF_8));
                    } catch (IOException cancelledByClient) {
                        // Expected when the client cancels an oversized response stream.
                    }
                });
        try (var provider = provider(Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> provider.embed(List.of(PRIVATE_TEXT)))
                    .isInstanceOf(EmbeddingFailure.class);
        }
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deadlineIncludesDelayedHeadersAndPartiallyReceivedBodies(boolean sendHeaders)
            throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        behavior.set(
                exchange -> {
                    if (sendHeaders) {
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write('{');
                        exchange.getResponseBody().flush();
                    }
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
        try (var provider = provider(Duration.ofSeconds(1))) {
            long started = System.nanoTime();
            try {
                assertFailure(provider, EmbeddingFailure.Reason.TIMED_OUT);
                assertThat(entered.getCount()).isZero();
                assertThat(Duration.ofNanos(System.nanoTime() - started))
                        .isLessThan(Duration.ofSeconds(4));
            } finally {
                release.countDown();
            }
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void interruptionCancelsTheInFlightRequestAndPreservesTheCallingThreadFlag() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        behavior.set(
                exchange -> {
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
        var preserved = new AtomicBoolean();
        var unexpected = new AtomicReference<Throwable>();
        try (var provider = provider(Duration.ofSeconds(3))) {
            var caller =
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try {
                                            provider.embed(List.of(PRIVATE_TEXT));
                                        } catch (InterruptedException interrupted) {
                                            preserved.set(Thread.currentThread().isInterrupted());
                                        } catch (Exception failure) {
                                            unexpected.set(failure);
                                        }
                                    });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                caller.interrupt();
                caller.join(3000);
                assertThat(caller.isAlive()).isFalse();
                assertThat(preserved).isTrue();
                assertThat(unexpected).hasValue(null);
            } finally {
                caller.interrupt();
                release.countDown();
                caller.join(3000);
            }
        }
    }

    @Test
    void rejectsMalformedSuccessPayloadsAndWrongContentType() throws Exception {
        behavior.set(
                exchange -> reply(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}"));
        try (var provider = provider(Duration.ofSeconds(2))) {
            assertFailure(provider, EmbeddingFailure.Reason.INVALID_RESPONSE);
            behavior.set(
                    exchange -> {
                        exchange.getResponseHeaders().set("Content-Type", "text/html");
                        var body =
                                ("{\"data\":[" + entry(0, "1") + "]}")
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    });
            assertFailure(provider, EmbeddingFailure.Reason.INVALID_RESPONSE);
        }
        assertThat(calls).hasValue(2);
    }

    @Test
    void acceptsTheConfiguredBatchAtTheUnicodeInputBoundary() throws Exception {
        String data =
                java.util.stream.IntStream.range(0, 8)
                        .mapToObj(index -> entry(index, "1"))
                        .collect(java.util.stream.Collectors.joining(","));
        behavior.set(exchange -> reply(exchange, 200, "{\"data\":[" + data + "]}"));
        try (var provider = provider(Duration.ofSeconds(3))) {
            assertThat(provider.embed(java.util.Collections.nCopies(8, "📚".repeat(1200))))
                    .hasSize(8);
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void connectionFailureIsRetryableWithoutExposingTransportDetails() throws Exception {
        server.stop(0);
        try (var provider = provider(Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> provider.embed(List.of(PRIVATE_TEXT)))
                    .isInstanceOfSatisfying(
                            EmbeddingFailure.class,
                            failure -> {
                                assertThat(failure.reason())
                                        .isEqualTo(EmbeddingFailure.Reason.UNAVAILABLE);
                                assertThat(failure.getCause()).isNull();
                            });
        }
        assertThat(calls).hasValue(0);
    }

    private static void assertFailure(
            CloudflareEmbeddingProvider provider, EmbeddingFailure.Reason reason) {
        assertThatThrownBy(() -> provider.embed(List.of(PRIVATE_TEXT)))
                .isInstanceOfSatisfying(
                        EmbeddingFailure.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private CloudflareEmbeddingProvider provider(Duration requestTimeout) {
        var original = CloudflareEmbeddingProviderTest.settings();
        var settings =
                new CloudflareEmbeddingSettings(
                        "a".repeat(32),
                        "synthetic-test-token",
                        original.model(),
                        Duration.ofMillis(500),
                        requestTimeout,
                        8);
        return new CloudflareEmbeddingProvider(settings, CLOCK, endpoint());
    }

    private URI endpoint() {
        return URI.create(
                "http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/client/v4/accounts/"
                        + "a".repeat(32)
                        + "/ai/v1/embeddings");
    }

    private static String entry(int index, String value) {
        return CloudflareEmbeddingProviderTest.entry(index, value);
    }

    private static void reply(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
