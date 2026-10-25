package dev.secondbrain.embeddings;

import static dev.secondbrain.embeddings.EmbeddingFailure.Reason.ACCESS_DENIED;
import static dev.secondbrain.embeddings.EmbeddingFailure.Reason.INVALID_INPUT;
import static dev.secondbrain.embeddings.EmbeddingFailure.Reason.INVALID_RESPONSE;
import static dev.secondbrain.embeddings.EmbeddingFailure.Reason.MODEL_UNAVAILABLE;
import static dev.secondbrain.embeddings.EmbeddingFailure.Reason.QUOTA_EXHAUSTED;
import static dev.secondbrain.embeddings.EmbeddingFailure.Reason.RATE_LIMITED;
import static dev.secondbrain.embeddings.EmbeddingFailure.Reason.TIMED_OUT;
import static dev.secondbrain.embeddings.EmbeddingFailure.Reason.UNAVAILABLE;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** Bounded JSON protocol and fixed error classifications; never exposes provider messages. */
final class CloudflareEmbeddingProtocol {
    static final int MAXIMUM_RESPONSE_BYTES = 1024 * 1024;
    private final EmbeddingModel model;
    private final Clock clock;
    private final JsonMapper json =
            JsonMapper.builder(
                            JsonFactory.builder()
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxDocumentLength(MAXIMUM_RESPONSE_BYTES)
                                                    .maxTokenCount(80000)
                                                    .maxNestingDepth(8)
                                                    .maxStringLength(4096)
                                                    .maxNameLength(128)
                                                    .maxNumberLength(64)
                                                    .build())
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();

    CloudflareEmbeddingProtocol(EmbeddingModel model, Clock clock) {
        this.model = java.util.Objects.requireNonNull(model, "model");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    byte[] request(List<String> inputs) {
        try {
            return json.writeValueAsBytes(
                    Map.of("model", model.id(), "input", inputs, "encoding_format", "float"));
        } catch (JacksonException invalid) {
            throw new EmbeddingFailure(INVALID_INPUT, null, invalid);
        }
    }

    List<EmbeddingVector> response(byte[] body, int count) {
        return responseWithUsage(body, count).vectors();
    }

    Response responseWithUsage(byte[] body, int count) {
        try {
            if (body.length > MAXIMUM_RESPONSE_BYTES || count < 1 || count > 32)
                throw new IllegalArgumentException();
            var root = json.readTree(body);
            if (root == null || !root.isObject() || root.has("error") || root.has("errors"))
                throw new IllegalArgumentException();
            if (root.has("model")
                    && (!root.path("model").isString()
                            || !model.id().equals(root.path("model").stringValue())))
                throw new IllegalArgumentException();
            var data = root.path("data");
            if (!data.isArray() || data.size() != count) throw new IllegalArgumentException();
            var ordered = new EmbeddingVector[count];
            for (var item : data) {
                var index = item.path("index");
                if (!index.isIntegralNumber() || !index.canConvertToInt())
                    throw new IllegalArgumentException();
                int position = index.intValue();
                if (position < 0 || position >= count || ordered[position] != null)
                    throw new IllegalArgumentException();
                var components = item.path("embedding");
                if (!components.isArray() || components.size() != model.dimensions())
                    throw new IllegalArgumentException();
                var values = new float[components.size()];
                for (int component = 0; component < values.length; component++) {
                    if (!components.get(component).isNumber()) throw new IllegalArgumentException();
                    values[component] = components.get(component).floatValue();
                }
                ordered[position] = new EmbeddingVector(model, values);
            }
            var reported = root.path("usage").path("prompt_tokens");
            var tokens =
                    reported.isIntegralNumber()
                                    && reported.canConvertToLong()
                                    && reported.longValue() >= 0
                                    && reported.longValue() <= 1_000_000
                            ? OptionalLong.of(reported.longValue())
                            : OptionalLong.empty();
            return new Response(List.copyOf(Arrays.asList(ordered)), tokens);
        } catch (JacksonException | IllegalArgumentException invalid) {
            throw new EmbeddingFailure(INVALID_RESPONSE, null, invalid);
        }
    }

    record Response(List<EmbeddingVector> vectors, OptionalLong inputTokens) {
        Response {
            vectors = List.copyOf(vectors);
            java.util.Objects.requireNonNull(inputTokens, "inputTokens");
        }
    }

    EmbeddingFailure failure(int status, byte[] body, String retryAfter) {
        int code = errorCode(body);
        var reason =
                switch (status) {
                    case 401, 403 -> code == 5035 ? MODEL_UNAVAILABLE : ACCESS_DENIED;
                    case 404 -> MODEL_UNAVAILABLE;
                    case 408 -> TIMED_OUT;
                    case 429 -> code == 3036 ? QUOTA_EXHAUSTED : RATE_LIMITED;
                    default -> {
                        if (status >= 500 && status <= 599) yield UNAVAILABLE;
                        if (code == 5007 || code == 3042) yield MODEL_UNAVAILABLE;
                        yield status >= 400 && status <= 499 ? INVALID_INPUT : INVALID_RESPONSE;
                    }
                };
        Instant retry =
                reason == RATE_LIMITED || reason == QUOTA_EXHAUSTED || reason == UNAVAILABLE
                        ? retryAfter(retryAfter)
                        : null;
        if (reason == QUOTA_EXHAUSTED) {
            var reset =
                    clock.instant()
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                            .plusDays(1)
                            .atStartOfDay(ZoneOffset.UTC)
                            .toInstant();
            if (retry == null || retry.isBefore(reset)) retry = reset;
        }
        return new EmbeddingFailure(reason, retry, null);
    }

    private int errorCode(byte[] body) {
        if (body.length > MAXIMUM_RESPONSE_BYTES) return 0;
        try {
            var root = json.readTree(body);
            if (root == null) return 0;
            var code = root.path("errors").path(0).path("code");
            if (code.isMissingNode()) code = root.path("error").path("code");
            if (code.isIntegralNumber() && code.canConvertToInt()) return code.intValue();
            if (code.isString() && code.stringValue().matches("[0-9]{1,5}"))
                return Integer.parseInt(code.stringValue());
        } catch (JacksonException invalid) {
            // HTTP status still gives a safe classification; never classify by private messages.
        }
        return 0;
    }

    private Instant retryAfter(String value) {
        if (value == null || value.length() > 128) return null;
        try {
            var now = clock.instant();
            var retry =
                    value.matches("[0-9]{1,10}")
                            ? now.plusSeconds(Long.parseLong(value))
                            : ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                                    .toInstant();
            return retry.isBefore(now) ? now : retry;
        } catch (DateTimeParseException | ArithmeticException invalid) {
            return null;
        }
    }
}
