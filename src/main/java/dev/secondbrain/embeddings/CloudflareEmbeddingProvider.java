package dev.secondbrain.embeddings;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One application attempt per call. Retry scheduling belongs to durable/interactive callers. */
public final class CloudflareEmbeddingProvider implements EmbeddingProvider, AutoCloseable {
    private static final int MAXIMUM_INPUT_CODE_POINTS = 1200;
    private static final int MAXIMUM_REQUEST_BYTES = 256 * 1024;
    private final CloudflareEmbeddingSettings settings;
    private final CloudflareEmbeddingProtocol protocol;
    private final URI endpoint;
    private final ThreadPoolExecutor network;
    private final HttpClient client;
    private final EmbeddingTelemetry telemetry;

    public CloudflareEmbeddingProvider(CloudflareEmbeddingSettings settings, Clock clock) {
        this(settings, clock, settings.endpoint(), EmbeddingTelemetry.disabled());
    }

    public CloudflareEmbeddingProvider(
            CloudflareEmbeddingSettings settings, Clock clock, EmbeddingTelemetry telemetry) {
        this(settings, clock, settings.endpoint(), telemetry);
    }

    /** Test-only loopback seam; production exposes no configurable endpoint override. */
    CloudflareEmbeddingProvider(CloudflareEmbeddingSettings settings, Clock clock, URI endpoint) {
        this(settings, clock, endpoint, EmbeddingTelemetry.disabled());
    }

    CloudflareEmbeddingProvider(
            CloudflareEmbeddingSettings settings,
            Clock clock,
            URI endpoint,
            EmbeddingTelemetry telemetry) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.equals(settings.endpoint())
                && !("http".equals(endpoint.getScheme())
                        && "127.0.0.1".equals(endpoint.getHost())
                        && endpoint.getUserInfo() == null
                        && endpoint.getQuery() == null
                        && endpoint.getFragment() == null)) {
            throw new IllegalArgumentException(
                    "Embedding endpoint must be Cloudflare or a test loopback endpoint");
        }
        protocol = new CloudflareEmbeddingProtocol(settings.model(), clock);
        network =
                new ThreadPoolExecutor(
                        2,
                        2,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(64),
                        Thread.ofPlatform().daemon(true).name("embedding-http-", 0).factory(),
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
            throw new EmbeddingFailure(EmbeddingFailure.Reason.UNAVAILABLE, null, unavailable);
        }
    }

    @Override
    public EmbeddingModel model() {
        return settings.model();
    }

    @Override
    public List<EmbeddingVector> embed(List<String> inputs) throws InterruptedException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("Embedding request interrupted");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Embedding I/O must run outside a database transaction");
        }
        var texts = validate(inputs);
        byte[] body = protocol.request(texts);
        if (body.length > MAXIMUM_REQUEST_BYTES)
            throw new EmbeddingFailure(EmbeddingFailure.Reason.INVALID_INPUT, null, null);
        var request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(settings.requestTimeout())
                        .header("Authorization", "Bearer " + settings.apiToken())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
        try (var operation = telemetry.start()) {
            try {
                var result = exchange(request, texts.size());
                operation.success(result.inputTokens());
                return result.vectors();
            } catch (EmbeddingFailure failed) {
                operation.failure(failed.reason());
                throw failed;
            } catch (InterruptedException interrupted) {
                operation.interrupted();
                throw interrupted;
            }
        }
    }

    private CloudflareEmbeddingProtocol.Response exchange(HttpRequest request, int count)
            throws InterruptedException {
        final java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> pending;
        try {
            pending =
                    client.sendAsync(
                            request,
                            HttpResponse.BodyHandlers.limiting(
                                    HttpResponse.BodyHandlers.ofByteArray(),
                                    CloudflareEmbeddingProtocol.MAXIMUM_RESPONSE_BYTES));
        } catch (RuntimeException unavailable) {
            throw new EmbeddingFailure(EmbeddingFailure.Reason.UNAVAILABLE, null, unavailable);
        }
        final HttpResponse<byte[]> response;
        try {
            // Byte-array completion includes the full body, so a slow body cannot evade the
            // deadline.
            response = pending.get(settings.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw new EmbeddingFailure(EmbeddingFailure.Reason.TIMED_OUT, null, timeout);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException unavailable) {
            var reason =
                    unavailable.getCause() instanceof HttpTimeoutException
                            ? EmbeddingFailure.Reason.TIMED_OUT
                            : EmbeddingFailure.Reason.UNAVAILABLE;
            throw new EmbeddingFailure(reason, null, unavailable.getCause());
        } catch (CancellationException unavailable) {
            throw new EmbeddingFailure(EmbeddingFailure.Reason.UNAVAILABLE, null, unavailable);
        }
        if (response.statusCode() != 200)
            throw protocol.failure(
                    response.statusCode(),
                    response.body(),
                    response.headers().firstValue("Retry-After").orElse(null));
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw new EmbeddingFailure(EmbeddingFailure.Reason.INVALID_RESPONSE, null, null);
        }
        return protocol.responseWithUsage(response.body(), count);
    }

    private List<String> validate(List<String> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > settings.batchSize()) {
            throw new EmbeddingFailure(EmbeddingFailure.Reason.INVALID_INPUT, null, null);
        }
        final List<String> texts;
        try {
            texts = List.copyOf(inputs);
        } catch (NullPointerException invalid) {
            throw new EmbeddingFailure(EmbeddingFailure.Reason.INVALID_INPUT, null, invalid);
        }
        if (texts.size() > settings.batchSize()) {
            throw new EmbeddingFailure(EmbeddingFailure.Reason.INVALID_INPUT, null, null);
        }
        for (var text : texts) {
            if (text.length() > MAXIMUM_INPUT_CODE_POINTS * 2
                    || text.isBlank()
                    || text.indexOf('\0') >= 0
                    || text.codePointCount(0, text.length()) > MAXIMUM_INPUT_CODE_POINTS) {
                throw new EmbeddingFailure(EmbeddingFailure.Reason.INVALID_INPUT, null, null);
            }
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (Character.isHighSurrogate(character)) {
                    if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                        throw new EmbeddingFailure(
                                EmbeddingFailure.Reason.INVALID_INPUT, null, null);
                    }
                } else if (Character.isLowSurrogate(character)) {
                    throw new EmbeddingFailure(EmbeddingFailure.Reason.INVALID_INPUT, null, null);
                }
            }
        }
        return texts;
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
