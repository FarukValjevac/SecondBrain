package dev.secondbrain.questions;

import java.net.URI;
import java.time.Duration;

/** Explicit opt-in connection settings. Secrets must never enter generated diagnostics. */
public final class CloudflareChatSettings {
    public static final String LLAMA_3_3_70B_ID = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
    private final String accountId;
    private final String apiToken;
    private final String model;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int maximumOutputTokens;

    public CloudflareChatSettings(
            String accountId,
            String apiToken,
            String model,
            Duration connectTimeout,
            Duration requestTimeout,
            int maximumOutputTokens) {
        if (accountId == null || !accountId.matches("[a-fA-F0-9]{32}"))
            throw new IllegalArgumentException(
                    "Cloudflare account ID must contain 32 hexadecimal characters");
        if (apiToken == null || !apiToken.matches("[!-~]{1,4096}"))
            throw new IllegalArgumentException(
                    "Cloudflare API token must be nonempty printable ASCII without spaces");
        if (!LLAMA_3_3_70B_ID.equals(model))
            throw new IllegalArgumentException(
                    "Unsupported chat model; an explicit supported choice is required");
        requireDuration(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(10));
        requireDuration(requestTimeout, connectTimeout, Duration.ofSeconds(60));
        if (maximumOutputTokens < 1 || maximumOutputTokens > 2048)
            throw new IllegalArgumentException(
                    "Chat output token limit must be between 1 and 2048");
        this.accountId = accountId;
        this.apiToken = apiToken;
        this.model = model;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.maximumOutputTokens = maximumOutputTokens;
    }

    private static void requireDuration(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0)
            throw new IllegalArgumentException("Chat timeouts are outside the supported bounds");
    }

    public URI endpoint() {
        return URI.create(
                "https://api.cloudflare.com/client/v4/accounts/"
                        + accountId
                        + "/ai/v1/chat/completions");
    }

    public String apiToken() {
        return apiToken;
    }

    public String model() {
        return model;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public int maximumOutputTokens() {
        return maximumOutputTokens;
    }

    @Override
    public String toString() {
        return "CloudflareChatSettings[model="
                + model
                + ", maximumOutputTokens="
                + maximumOutputTokens
                + "]";
    }
}
