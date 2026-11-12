package dev.secondbrain.config;

import io.micrometer.registry.otlp.OtlpMetricsSender;

import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One best-effort local OTLP request, with no response-body logging or retry queue. */
final class LocalMetricsSender implements OtlpMetricsSender, AutoCloseable {
    private final URI endpoint;
    private final Semaphore permit = new Semaphore(1);
    private final ThreadPoolExecutor executor;
    private final HttpClient client;

    LocalMetricsSender(String endpoint) {
        TelemetryConfiguration.validateEndpoint(endpoint);
        this.endpoint = URI.create(endpoint);
        if (!Set.of("127.0.0.1", "localhost", "[::1]").contains(this.endpoint.getHost())
                || !"/v1/metrics".equals(this.endpoint.getPath()))
            throw new IllegalArgumentException(
                    "Metrics export requires a loopback /v1/metrics endpoint");
        executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(4),
                        task -> {
                            var thread = new Thread(task, "local-metrics-http");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(1))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .executor(executor)
                        .build();
    }

    @Override
    public void send(Request request) {
        if (!permit.tryAcquire()) {
            failed();
            return;
        }
        java.util.concurrent.CompletableFuture<HttpResponse<Void>> pending = null;
        try {
            if (request.getMetricsData().length > 256 * 1024 || client.isTerminated()) {
                failed();
                return;
            }
            // Ignore request-supplied addresses/headers: credentials and redirects never leave
            // this fixed local boundary. Read completion, not just response headers, is bounded.
            var http =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(Duration.ofSeconds(2))
                            .header("Content-Type", "application/x-protobuf")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(request.getMetricsData()))
                            .build();
            pending = client.sendAsync(http, HttpResponse.BodyHandlers.discarding());
            if (pending.get(2, TimeUnit.SECONDS).statusCode() / 100 != 2) failed();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failed();
        } catch (ExecutionException | TimeoutException | RuntimeException unavailable) {
            failed();
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            permit.release();
        }
    }

    private static void failed() {
        try {
            LoggerFactory.getLogger(LocalMetricsSender.class)
                    .atWarn()
                    .addKeyValue("code", "TELEMETRY_UNAVAILABLE")
                    .log("Local metrics snapshot could not be exported");
        } catch (RuntimeException unavailable) {
            // Diagnostics cannot affect an application operation or publisher shutdown.
        }
    }

    @Override
    public void close() {
        client.shutdownNow();
        executor.shutdownNow();
    }
}
