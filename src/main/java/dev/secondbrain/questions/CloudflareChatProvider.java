package dev.secondbrain.questions;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One bounded attempt per interactive call. No automatic retry, model fallback or tool execution.
 */
public final class CloudflareChatProvider implements ChatProvider, AutoCloseable {
    private final CloudflareChatSettings settings;
    private final CloudflareChatProtocol protocol;
    private final URI endpoint;
    private final ThreadPoolExecutor network;
    private final HttpClient client;
    // Shared transport capacity only; request/response state stays local to generate().
    private final Semaphore capacity = new Semaphore(2);

    public CloudflareChatProvider(CloudflareChatSettings settings, Clock clock) {
        this(settings, clock, settings.endpoint());
    }

    /** Package-local loopback seam for isolated stub tests, never external configuration. */
    CloudflareChatProvider(CloudflareChatSettings settings, Clock clock, URI endpoint) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.equals(settings.endpoint())
                && !("http".equals(endpoint.getScheme())
                        && "127.0.0.1".equals(endpoint.getHost())
                        && endpoint.getUserInfo() == null
                        && endpoint.getQuery() == null
                        && endpoint.getFragment() == null))
            throw new IllegalArgumentException(
                    "Chat endpoint must be Cloudflare or a test loopback endpoint");
        protocol = new CloudflareChatProtocol(settings, clock);
        network =
                new ThreadPoolExecutor(
                        2,
                        2,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(32),
                        Thread.ofPlatform().daemon(true).name("chat-http-", 0).factory(),
                        new ThreadPoolExecutor.AbortPolicy());
        try {
            client =
                    HttpClient.newBuilder()
                            .executor(network)
                            .connectTimeout(settings.connectTimeout())
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
        } catch (RuntimeException unavailable) {
            network.shutdownNow();
            throw new ChatFailure(ChatFailure.Reason.UNAVAILABLE, null, unavailable);
        }
    }

    @Override
    public Reply generate(Request request) throws InterruptedException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("Chat request interrupted");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Chat I/O must run outside a database transaction");
        var body = protocol.request(request);
        if (!capacity.tryAcquire())
            throw new ChatFailure(ChatFailure.Reason.UNAVAILABLE, null, null);
        try {
            var outbound =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(settings.requestTimeout())
                            .header("Authorization", "Bearer " + settings.apiToken())
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build();
            return exchange(outbound);
        } finally {
            capacity.release();
        }
    }

    private Reply exchange(HttpRequest request) throws InterruptedException {
        final java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> pending;
        try {
            pending =
                    client.sendAsync(
                            request,
                            HttpResponse.BodyHandlers.limiting(
                                    HttpResponse.BodyHandlers.ofByteArray(),
                                    CloudflareChatProtocol.MAXIMUM_RESPONSE_BYTES));
        } catch (RuntimeException unavailable) {
            throw new ChatFailure(ChatFailure.Reason.UNAVAILABLE, null, unavailable);
        }
        final HttpResponse<byte[]> response;
        try {
            // Deadline covers headers and the complete bounded body, not just connection setup.
            response = pending.get(settings.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw new ChatFailure(ChatFailure.Reason.TIMED_OUT, null, timeout);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException unavailable) {
            var reason =
                    unavailable.getCause() instanceof HttpTimeoutException
                            ? ChatFailure.Reason.TIMED_OUT
                            : ChatFailure.Reason.UNAVAILABLE;
            throw new ChatFailure(reason, null, unavailable.getCause());
        } catch (CancellationException unavailable) {
            throw new ChatFailure(ChatFailure.Reason.UNAVAILABLE, null, unavailable);
        }
        if (response.statusCode() != 200)
            throw protocol.failure(
                    response.statusCode(),
                    response.body(),
                    response.headers().firstValue("Retry-After").orElse(null));
        String type = response.headers().firstValue("Content-Type").orElse("");
        if (!type.split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
            throw new ChatFailure(ChatFailure.Reason.INVALID_RESPONSE, null, null);
        return protocol.response(response.body());
    }

    @Override
    public void close() {
        try {
            client.shutdownNow();
        } finally {
            network.shutdownNow();
        }
    }
}
