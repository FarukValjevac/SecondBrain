package dev.secondbrain.embeddings;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Validated connection policy. Credentials are deliberately excluded from generated diagnostics.
 */
public final class CloudflareEmbeddingSettings {
    private final String accountId;
    private final String apiToken;
    private final EmbeddingModel model;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int batchSize;

    public CloudflareEmbeddingSettings(
            String accountId,
            String apiToken,
            EmbeddingModel model,
            Duration connectTimeout,
            Duration requestTimeout,
            int batchSize) {
        if (accountId == null || !accountId.matches("[a-fA-F0-9]{32}")) {
            throw new IllegalArgumentException(
                    "Cloudflare account ID must contain 32 hexadecimal characters");
        }
        if (apiToken == null || !apiToken.matches("[!-~]{1,4096}")) {
            throw new IllegalArgumentException(
                    "Cloudflare API token must be nonempty printable ASCII without spaces");
        }
        this.accountId = accountId;
        this.apiToken = apiToken;
        this.model = Objects.requireNonNull(model, "model");
        requireRange(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(10));
        requireRange(requestTimeout, connectTimeout, Duration.ofSeconds(60));
        if (batchSize < 1 || batchSize > 32) {
            throw new IllegalArgumentException("Embedding batch size must be between 1 and 32");
        }
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.batchSize = batchSize;
    }

    private static void requireRange(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "Embedding timeouts are outside the supported bounds");
        }
    }

    /**
     * Production requests have one fixed HTTPS origin, with no configurable credential destination.
     */
    public URI endpoint() {
        return URI.create(
                "https://api.cloudflare.com/client/v4/accounts/" + accountId + "/ai/v1/embeddings");
    }

    public String apiToken() {
        return apiToken;
    }

    public EmbeddingModel model() {
        return model;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public int batchSize() {
        return batchSize;
    }

    @Override
    public String toString() {
        return "CloudflareEmbeddingSettings[model="
                + model.id()
                + ", dimensions="
                + model.dimensions()
                + "]";
    }
}
