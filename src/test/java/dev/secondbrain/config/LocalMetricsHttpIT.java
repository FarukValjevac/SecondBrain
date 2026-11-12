package dev.secondbrain.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sun.net.httpserver.HttpServer;

import io.micrometer.registry.otlp.OtlpMetricsSender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@ExtendWith(OutputCaptureExtension.class)
class LocalMetricsHttpIT {
    @ParameterizedTest
    @ValueSource(ints = {200, 302, 429, 500})
    void sendsOneFixedLocalRequestWithoutHeadersRedirectsRetriesOrErrorBodyLogging(
            int status, CapturedOutput output) throws Exception {
        var calls = new AtomicInteger();
        var body = new AtomicReference<byte[]>();
        var header = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/metrics",
                exchange -> {
                    calls.incrementAndGet();
                    body.set(exchange.getRequestBody().readAllBytes());
                    header.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    exchange.getResponseHeaders().set("Location", "/private-redirect-sentinel");
                    var response = "private-response-sentinel".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, response.length);
                    try (var stream = exchange.getResponseBody()) {
                        stream.write(response);
                    }
                });
        server.start();
        try (var sender = new LocalMetricsSender(endpoint(server))) {
            sender.send(
                    OtlpMetricsSender.Request.builder(new byte[] {1, 2, 3})
                            .address("https://private-target-sentinel.example/v1/metrics")
                            .headers(Map.of("Authorization", "private-token-sentinel"))
                            .build());
            assertThat(calls).hasValue(1);
            assertThat(body.get()).containsExactly(1, 2, 3);
            assertThat(header.get()).isNull();
        } finally {
            server.stop(0);
        }
        assertThat(output).doesNotContain("private-");
    }

    @Test
    void boundsWholeBodyWaitRejectsConcurrentSendsAndRecovers(CapturedOutput output)
            throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/metrics",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    int call = calls.incrementAndGet();
                    exchange.sendResponseHeaders(200, 1);
                    if (call == 1) {
                        entered.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    try (var stream = exchange.getResponseBody()) {
                        stream.write(1);
                    }
                });
        server.start();
        try (var sender = new LocalMetricsSender(endpoint(server));
                var caller = Executors.newSingleThreadExecutor()) {
            var request = OtlpMetricsSender.Request.builder(new byte[] {1}).build();
            var first = caller.submit(() -> sender.send(request));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatCode(() -> sender.send(request)).doesNotThrowAnyException();
            assertThat(calls).hasValue(1); // Busy snapshot was dropped, never queued.
            first.get(4, TimeUnit.SECONDS); // Full-body timeout, despite receiving headers.
            release.countDown();
            sender.send(request);
            assertThat(calls).hasValue(2);
        } finally {
            release.countDown();
            server.stop(0);
        }
        assertThat(output).contains("TELEMETRY_UNAVAILABLE");
    }

    @Test
    void shutdownCancelsAnActiveExportWithoutWaitingForBackendRecovery() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/metrics",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        server.start();
        var sender = new LocalMetricsSender(endpoint(server));
        try (var caller = Executors.newSingleThreadExecutor()) {
            var pending =
                    caller.submit(
                            () ->
                                    sender.send(
                                            OtlpMetricsSender.Request.builder(new byte[] {1})
                                                    .build()));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            sender.close();
            pending.get(3, TimeUnit.SECONDS);
        } finally {
            sender.close();
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void oversizedClosedAndInterruptedExportsDoNotAffectCallerOrLeakData() {
        var sender = new LocalMetricsSender("http://127.0.0.1:1/v1/metrics");
        sender.send(OtlpMetricsSender.Request.builder(new byte[256 * 1024 + 1]).build());
        sender.close();
        sender.close();
        assertThatCode(() -> sender.send(OtlpMetricsSender.Request.builder(new byte[] {1}).build()))
                .doesNotThrowAnyException();
        try (var other = new LocalMetricsSender("http://127.0.0.1:1/v1/metrics")) {
            Thread.currentThread().interrupt();
            try {
                other.send(OtlpMetricsSender.Request.builder(new byte[] {1}).build());
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    private static String endpoint(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/metrics";
    }
}
