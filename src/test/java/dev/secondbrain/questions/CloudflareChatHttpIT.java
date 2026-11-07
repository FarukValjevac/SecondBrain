package dev.secondbrain.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
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
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Only a synthetic-credential loopback server; binding failures fail required verification. */
@ExtendWith(OutputCaptureExtension.class)
class CloudflareChatHttpIT {
    private HttpServer server;
    private ThreadPoolExecutor threads;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<Handler> behavior = new AtomicReference<>();

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void startLoopbackServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        threads =
                new ThreadPoolExecutor(
                        3, 3, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));
        behavior.set(
                exchange ->
                        reply(
                                exchange,
                                200,
                                CloudflareChatProtocolTest.success(
                                        CloudflareChatProtocolTest.answer())));
        server.createContext(
                "/",
                exchange -> {
                    calls.incrementAndGet();
                    try (exchange) {
                        behavior.get().handle(exchange);
                    }
                });
        server.setExecutor(threads);
        server.start();
    }

    @AfterEach
    void stopOwnedResources() throws InterruptedException {
        if (server != null) server.stop(0);
        if (threads != null) {
            threads.shutdownNow();
            assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void sendsOneAuthorizedStructuredRequestAndReturnsOnlyAValidatedCandidate(CapturedOutput output)
            throws Exception {
        var method = new AtomicReference<String>();
        var auth = new AtomicReference<String>();
        var received = new AtomicReference<byte[]>();
        var path = new AtomicReference<String>();
        behavior.set(
                exchange -> {
                    method.set(exchange.getRequestMethod());
                    auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    path.set(exchange.getRequestURI().getPath());
                    received.set(exchange.getRequestBody().readAllBytes());
                    reply(
                            exchange,
                            200,
                            CloudflareChatProtocolTest.success(
                                    CloudflareChatProtocolTest.answer()));
                });
        try (var provider = provider(Duration.ofSeconds(2))) {
            assertThat(provider.generate(CloudflareChatProtocolTest.input()))
                    .isEqualTo(CloudflareChatProtocolTest.answer());
        }
        assertThat(calls).hasValue(1);
        assertThat(method).hasValue("POST");
        assertThat(auth).hasValue("Bearer synthetic-chat-token");
        assertThat(path)
                .hasValue("/client/v4/accounts/" + "a".repeat(32) + "/ai/v1/chat/completions");
        var body = new JsonMapper().readTree(received.get());
        assertThat(body.path("response_format").path("type").stringValue())
                .isEqualTo("json_schema");
        var reference =
                new JsonMapper()
                        .readTree(body.path("messages").get(1).path("content").stringValue());
        assertThat(reference.path("question").stringValue())
                .isEqualTo(CloudflareChatProtocolTest.input().question());
        assertThat(reference.path("evidence").get(0).path("passage_id").stringValue())
                .isEqualTo(CloudflareChatProtocolTest.PASSAGE.toString());
        assertPrivateOutputAbsent(output);
    }

    @Test
    void returnsExplicitInsufficientInformationWithoutAnotherAttempt() throws Exception {
        var insufficient =
                new ChatProvider.Reply(
                        ChatProvider.Outcome.INSUFFICIENT_INFORMATION, "", java.util.List.of());
        behavior.set(
                exchange -> reply(exchange, 200, CloudflareChatProtocolTest.success(insufficient)));
        try (var provider = provider(Duration.ofSeconds(2))) {
            assertThat(provider.generate(CloudflareChatProtocolTest.input()))
                    .isEqualTo(insufficient);
        }
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({
        "401,0,ACCESS_DENIED",
        "403,5035,MODEL_UNAVAILABLE",
        "429,3036,QUOTA_EXHAUSTED",
        "429,3040,RATE_LIMITED",
        "503,0,UNAVAILABLE"
    })
    void classifiesFailuresWithoutRetriesFallbackOrPrivateBodies(
            int status, int code, ChatFailure.Reason reason, CapturedOutput output) {
        behavior.set(
                exchange -> {
                    exchange.getResponseHeaders().set("Retry-After", "60");
                    reply(
                            exchange,
                            status,
                            ("{\"errors\":[{\"code\":"
                                            + code
                                            + ",\"message\":\""
                                            + CloudflareChatProtocolTest.SECRET
                                            + "\"}]}")
                                    .getBytes(StandardCharsets.UTF_8));
                });
        try (var provider = provider(Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> provider.generate(CloudflareChatProtocolTest.input()))
                    .isInstanceOfSatisfying(
                            ChatFailure.class,
                            failure -> {
                                assertThat(failure.reason()).isEqualTo(reason);
                                assertThat(failure.getCause()).isNull();
                                if (reason == ChatFailure.Reason.QUOTA_EXHAUSTED)
                                    assertThat(failure.retryAt())
                                            .contains(Instant.parse("2030-01-02T00:00:00Z"));
                            });
        }
        assertThat(calls).hasValue(1);
        assertPrivateOutputAbsent(output);
    }

    @Test
    void neverFollowsRedirectsWithCredentials() {
        behavior.set(
                exchange -> {
                    exchange.getResponseHeaders()
                            .set("Location", endpoint().resolve("/redirect-target").toString());
                    reply(exchange, 302, new byte[0]);
                });
        try (var provider = provider(Duration.ofSeconds(2))) {
            assertFailure(provider, ChatFailure.Reason.INVALID_RESPONSE);
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void boundsAChunkedResponseBeforeJsonDecoding() {
        var release = new CountDownLatch(1);
        behavior.set(
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        exchange.getResponseBody()
                                .write(new byte[CloudflareChatProtocol.MAXIMUM_RESPONSE_BYTES + 1]);
                        exchange.getResponseBody().flush();
                        // Keep the stream open: rejection must come from the byte cap, not EOF.
                        await(release);
                    } catch (IOException cancelled) {
                        /* Client cancelled the oversized body. */
                    }
                });
        try (var provider = provider(Duration.ofSeconds(2))) {
            try {
                assertFailure(provider, ChatFailure.Reason.UNAVAILABLE);
            } finally {
                release.countDown();
            }
        }
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deadlineCoversDelayedHeadersAndAPartiallyReceivedBody(boolean sendHeaders) {
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
                    await(release);
                });
        try (var provider = provider(Duration.ofSeconds(1))) {
            try {
                assertFailure(provider, ChatFailure.Reason.TIMED_OUT);
                assertThat(entered.getCount()).isZero();
            } finally {
                release.countDown();
            }
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void interruptionCancelsAnInflightRequestAndRestoresTheCallerFlag() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        behavior.set(
                exchange -> {
                    entered.countDown();
                    await(release);
                });
        var interrupted = new AtomicBoolean();
        var unexpected = new AtomicReference<Exception>();
        try (var provider = provider(Duration.ofSeconds(3))) {
            var caller =
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try {
                                            provider.generate(CloudflareChatProtocolTest.input());
                                        } catch (InterruptedException cancelled) {
                                            interrupted.set(Thread.currentThread().isInterrupted());
                                        } catch (Exception failed) {
                                            unexpected.set(failed);
                                        }
                                    });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                caller.interrupt();
                caller.join(3000);
                assertThat(caller.isAlive()).isFalse();
                assertThat(interrupted).isTrue();
                assertThat(unexpected).hasValue(null);
            } finally {
                release.countDown();
                caller.interrupt();
                caller.join(3000);
            }
        }
    }

    @Test
    void refusesAThirdConcurrentCallWithoutQueuingItAndRecoversCapacity() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        behavior.set(
                exchange -> {
                    entered.countDown();
                    await(release);
                    reply(
                            exchange,
                            200,
                            CloudflareChatProtocolTest.success(
                                    CloudflareChatProtocolTest.answer()));
                });
        try (var provider = provider(Duration.ofSeconds(4));
                var callers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> provider.generate(CloudflareChatProtocolTest.input()));
            var second =
                    callers.submit(() -> provider.generate(CloudflareChatProtocolTest.input()));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertFailure(provider, ChatFailure.Reason.UNAVAILABLE);
                assertThat(calls).hasValue(2);
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS))
                    .isEqualTo(CloudflareChatProtocolTest.answer());
            assertThat(second.get(5, TimeUnit.SECONDS))
                    .isEqualTo(CloudflareChatProtocolTest.answer());
            assertThat(provider.generate(CloudflareChatProtocolTest.input()))
                    .isEqualTo(CloudflareChatProtocolTest.answer());
        }
        assertThat(calls).hasValue(3);
    }

    @Test
    void rejectsMalformedBodiesAndWrongContentTypes() {
        behavior.set(
                exchange ->
                        reply(exchange, 200, "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8)));
        try (var provider = provider(Duration.ofSeconds(2))) {
            assertFailure(provider, ChatFailure.Reason.INVALID_RESPONSE);
            behavior.set(
                    exchange -> {
                        exchange.getResponseHeaders().set("Content-Type", "text/html");
                        var body =
                                CloudflareChatProtocolTest.success(
                                        CloudflareChatProtocolTest.answer());
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    });
            assertFailure(provider, ChatFailure.Reason.INVALID_RESPONSE);
        }
        assertThat(calls).hasValue(2);
    }

    @Test
    void connectionFailureDoesNotLeakTransportDetails() {
        server.stop(0);
        try (var provider = provider(Duration.ofSeconds(2))) {
            assertFailure(provider, ChatFailure.Reason.UNAVAILABLE);
        }
        assertThat(calls).hasValue(0);
    }

    private CloudflareChatProvider provider(Duration timeout) {
        return new CloudflareChatProvider(
                CloudflareChatProtocolTest.settings(timeout),
                CloudflareChatProtocolTest.CLOCK,
                endpoint());
    }

    private URI endpoint() {
        return URI.create(
                "http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/client/v4/accounts/"
                        + "a".repeat(32)
                        + "/ai/v1/chat/completions");
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertFailure(CloudflareChatProvider provider, ChatFailure.Reason reason) {
        assertThatThrownBy(() -> provider.generate(CloudflareChatProtocolTest.input()))
                .isInstanceOfSatisfying(
                        ChatFailure.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private static void assertPrivateOutputAbsent(CapturedOutput output) {
        assertThat(output)
                .doesNotContain(
                        "private-question-sentinel",
                        "private-evidence-sentinel",
                        "private-answer-sentinel",
                        CloudflareChatProtocolTest.SECRET,
                        "synthetic-chat-token",
                        "a".repeat(32));
    }
}
