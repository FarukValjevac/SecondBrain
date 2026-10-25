package dev.secondbrain.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

class CloudflareEmbeddingProviderTest {
    private static final EmbeddingModel MODEL = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2030-01-01T23:59:30Z"), ZoneOffset.UTC);
    private final CloudflareEmbeddingProtocol protocol =
            new CloudflareEmbeddingProtocol(MODEL, CLOCK);

    @Test
    void extractsOnlyReportedInputTokensAlongsideValidatedVectors() {
        var result =
                protocol.responseWithUsage(
                        bytes(
                                "{\"data\":["
                                        + entry(0, "1")
                                        + "],\"usage\":{\"prompt_tokens\":37,\"total_tokens\":37}}"),
                        1);
        assertThat(result.inputTokens()).hasValue(37);
        assertThat(result.vectors()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "{}",
                "{\"prompt_tokens\":-1}",
                "{\"prompt_tokens\":1.5}",
                "{\"prompt_tokens\":\"private-usage-sentinel\"}",
                "{\"prompt_tokens\":1000001}",
                "{\"prompt_tokens\":999999999999999999999999}",
                "{\"total_tokens\":37}"
            })
    void missingOrInvalidOptionalUsageDoesNotInvalidateGoodVectors(String usage) {
        var result =
                protocol.responseWithUsage(
                        bytes("{\"data\":[" + entry(0, "1") + "],\"usage\":" + usage + "}"), 1);
        assertThat(result.inputTokens()).isEmpty();
        assertThat(result.vectors()).hasSize(1);
    }

    @Test
    void encodesLiteralUnicodeAndReturnsValidatedVectorsInInputOrder() {
        var request =
                new JsonMapper().readTree(protocol.request(List.of("Café 📚\n\"note\"", "Second")));
        assertThat(request.path("input").get(0).stringValue()).isEqualTo("Café 📚\n\"note\"");
        assertThat(request.path("model").stringValue()).isEqualTo(MODEL.id());
        assertThat(request.path("encoding_format").stringValue()).isEqualTo("float");
        var vectors =
                protocol.response(
                        bytes("{\"data\":[" + entry(1, "0.75") + "," + entry(0, "0.25") + "]}"), 2);
        assertThat(vectors.get(0).values()[0]).isEqualTo(0.25f);
        assertThat(vectors.get(1).values()[0]).isEqualTo(0.75f);
        assertThatThrownBy(vectors::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsMalformedUntrustedResponsesWithoutPrivateDiagnosticText(String response) {
        try {
            protocol.response(bytes(response), 1);
            org.junit.jupiter.api.Assertions.fail("Invalid response was accepted");
        } catch (EmbeddingFailure failure) {
            assertThat(failure.reason()).isEqualTo(EmbeddingFailure.Reason.INVALID_RESPONSE);
            assertThat(failure.retryable()).isFalse();
            assertThat(failure.getCause()).isNull();
            var trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            assertThat(trace.toString()).doesNotContain("private-response-sentinel", "0.125");
        }
    }

    static Stream<String> invalidResponses() {
        String valid = "{\"data\":[" + entry(0, "0.125") + "]}";
        return Stream.of(
                "",
                "null",
                "private-response-sentinel",
                valid + " {}",
                "{\"data\":[],\"data\":[" + entry(0, "0.125") + "]}",
                "{\"model\":\"other\",\"data\":[" + entry(0, "0.125") + "]}",
                "{\"data\":[]}",
                "{\"result\":{\"data\":[]}}",
                "{\"data\":[" + entry(1, "0.125") + "]}",
                valid.replace("\"index\":0", "\"index\":0.5"),
                valid.replace("\"index\":0,", ""),
                "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}",
                "{\"data\":[" + entry(0, "\"private-response-sentinel\"") + "]}",
                "{\"data\":[" + entry(0, "0") + "]}",
                "{\"data\":[" + entry(0, "1e100") + "]}");
    }

    @Test
    void rejectsDuplicateIndexesOversizedAndDeepResponses() {
        assertThatThrownBy(
                        () ->
                                protocol.response(
                                        bytes(
                                                "{\"data\":["
                                                        + entry(0, "1")
                                                        + ","
                                                        + entry(0, "1")
                                                        + "]}"),
                                        2))
                .isInstanceOf(EmbeddingFailure.class);
        assertThatThrownBy(
                        () ->
                                protocol.response(
                                        new byte
                                                [CloudflareEmbeddingProtocol.MAXIMUM_RESPONSE_BYTES
                                                        + 1],
                                        1))
                .isInstanceOf(EmbeddingFailure.class);
        assertThatThrownBy(
                        () ->
                                protocol.response(
                                        bytes(
                                                "{\"data\":["
                                                        + entry(0, "1")
                                                        + "],\"extra\":"
                                                        + "[".repeat(20)
                                                        + "0"
                                                        + "]".repeat(20)
                                                        + "}"),
                                        1))
                .isInstanceOf(EmbeddingFailure.class);
    }

    @ParameterizedTest
    @CsvSource({
        "401,0,ACCESS_DENIED",
        "403,0,ACCESS_DENIED",
        "403,5035,MODEL_UNAVAILABLE",
        "400,5007,MODEL_UNAVAILABLE",
        "404,3042,MODEL_UNAVAILABLE",
        "413,3006,INVALID_INPUT",
        "408,3007,TIMED_OUT",
        "429,3040,RATE_LIMITED",
        "429,3036,QUOTA_EXHAUSTED",
        "500,0,UNAVAILABLE",
        "503,0,UNAVAILABLE",
        "302,0,INVALID_RESPONSE"
    })
    void classifiesOnlyDocumentedCodesAndHttpStatus(
            int status, int code, EmbeddingFailure.Reason reason) {
        var failure =
                protocol.failure(
                        status,
                        bytes(
                                "{\"errors\":[{\"code\":"
                                        + code
                                        + ",\"message\":\"private-response-sentinel\"}]}"),
                        null);
        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.toString()).doesNotContain("private-response-sentinel");
    }

    @Test
    void preservesRetryHintsAndDailyResetWithoutSleepingOrGuessingFromMessages() {
        var limited = protocol.failure(429, bytes("{}"), "15");
        assertThat(limited.retryAt()).contains(CLOCK.instant().plusSeconds(15));
        var date = protocol.failure(503, bytes("{}"), "Wed, 2 Jan 2030 00:01:00 GMT");
        assertThat(date.retryAt()).contains(Instant.parse("2030-01-02T00:01:00Z"));
        var quota = protocol.failure(429, bytes("{\"error\":{\"code\":\"3036\"}}"), "120");
        assertThat(quota.retryAt()).contains(CLOCK.instant().plusSeconds(120));
        assertThat(protocol.failure(429, bytes("{\"errors\":[{\"code\":3036}]}"), null).retryAt())
                .contains(Instant.parse("2030-01-02T00:00:00Z"));
        assertThat(
                        protocol.failure(
                                        429,
                                        bytes("{\"error\":{\"message\":\"daily quota exceeded\"}}"),
                                        "bad")
                                .reason())
                .isEqualTo(EmbeddingFailure.Reason.RATE_LIMITED);
        assertThat(protocol.failure(429, bytes("not JSON"), "bad").retryAt()).isEmpty();
        assertThat(protocol.failure(403, bytes("{}"), "120").retryAt()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void rejectsBadInputsBeforeAttemptingAnyNetworkRequest(List<String> inputs) throws Exception {
        try (var provider =
                new CloudflareEmbeddingProvider(
                        settings(), CLOCK, URI.create("http://127.0.0.1:9/test"))) {
            assertThatThrownBy(() -> provider.embed(inputs))
                    .isInstanceOfSatisfying(
                            EmbeddingFailure.class,
                            failure ->
                                    assertThat(failure.reason())
                                            .isEqualTo(EmbeddingFailure.Reason.INVALID_INPUT));
        }
    }

    static Stream<List<String>> invalidInputs() {
        return Stream.of(
                null,
                List.of(),
                Collections.nCopies(9, "Note"),
                List.of(" "),
                Arrays.asList((String) null),
                List.of("note\0"),
                List.of("\ud800"),
                List.of("\udc00"),
                List.of("📚".repeat(1201)));
    }

    @Test
    void refusesTransactionsAndPreservesPreexistingInterruptionWithoutNetworkCalls()
            throws Exception {
        try (var provider =
                new CloudflareEmbeddingProvider(
                        settings(), CLOCK, URI.create("http://127.0.0.1:9/test"))) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> provider.embed(List.of("Note")))
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> provider.embed(List.of("Note")))
                        .isInstanceOf(InterruptedException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void supportsTheMaximumResponseBatchWithinTheParserBudget() {
        String data =
                java.util.stream.IntStream.range(0, 32)
                        .mapToObj(index -> entry(index, "1"))
                        .collect(java.util.stream.Collectors.joining(","));
        assertThat(protocol.response(bytes("{\"data\":[" + data + "]}"), 32)).hasSize(32);
    }

    @Test
    void rejectsUnexpectedEndpointsBeforeAllocatingTransportResources() {
        assertThatThrownBy(
                        () ->
                                new CloudflareEmbeddingProvider(
                                        settings(), CLOCK, URI.create("https://other.invalid/")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closedTransportFailuresKeepASafePublicCauseChain() throws Exception {
        var provider =
                new CloudflareEmbeddingProvider(
                        settings(), CLOCK, URI.create("http://127.0.0.1:9/test"));
        provider.close();
        assertThatThrownBy(() -> provider.embed(List.of("Private input")))
                .isInstanceOfSatisfying(
                        EmbeddingFailure.class,
                        failure -> {
                            assertThat(failure.reason())
                                    .isEqualTo(EmbeddingFailure.Reason.UNAVAILABLE);
                            assertThat(failure.getCause()).isNull();
                        });
    }

    static CloudflareEmbeddingSettings settings() {
        return new CloudflareEmbeddingSettings(
                "a".repeat(32),
                "synthetic-test-token",
                MODEL,
                Duration.ofMillis(100),
                Duration.ofSeconds(2),
                8);
    }

    static String entry(int index, String firstComponent) {
        return "{\"index\":"
                + index
                + ",\"embedding\":["
                + firstComponent
                + ","
                + String.join(",", Collections.nCopies(1023, "0"))
                + "]}";
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
