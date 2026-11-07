package dev.secondbrain.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchResult;
import dev.secondbrain.search.SearchScope;
import dev.secondbrain.search.SearchService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@ExtendWith(OutputCaptureExtension.class)
class QuestionTelemetryTest {
    private static final String PRIVATE = "private-query-prompt-document-answer-token-sentinel";
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final ObservationRegistry observations = ObservationRegistry.create();

    @AfterEach
    void closeMeters() {
        meters.close();
    }

    private QuestionTelemetry telemetry() {
        observations
                .observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters));
        return new QuestionTelemetry(meters, observations);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 37})
    void providerDurationAndReportedTokensAreRecordedOnceWithoutEstimatingMissingUsage(long count) {
        var telemetry = telemetry();
        var operation = telemetry.startProvider();
        clock.add(Duration.ofMillis(125));
        operation.candidate(ChatProvider.Outcome.ANSWERED);
        var tokens = count < 0 ? OptionalLong.empty() : OptionalLong.of(count);
        operation.usage(tokens, OptionalLong.empty(), OptionalLong.empty());
        operation.close();
        operation.close();
        assertCounter("secondbrain.chat.requests", "candidate_answer", 1);
        var timer =
                meters.get("secondbrain.chat.request.duration")
                        .tag("outcome", "candidate_answer")
                        .timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(125);
        assertThat(meters.get("secondbrain.chat.tokens").tag("kind", "input").counter().count())
                .isEqualTo(Math.max(0, count));
        assertThat(
                        meters.get("secondbrain.chat.usage.reports")
                                .tag("kind", "input")
                                .counter()
                                .count())
                .isEqualTo(count < 0 ? 0 : 1);
        for (String kind : List.of("output", "total")) {
            assertThat(meters.get("secondbrain.chat.tokens").tag("kind", kind).counter().count())
                    .isZero();
            assertThat(
                            meters.get("secondbrain.chat.usage.reports")
                                    .tag("kind", kind)
                                    .counter()
                                    .count())
                    .isZero();
        }
        assertCounter("secondbrain.question.requests", "candidate_answer", 0);
        assertSafeMeters();
    }

    @Test
    void noEvidenceAndEmptyScopeAreQuestionCompletionsWithoutProviderCallsOrUsage() {
        var search = mock(SearchService.class);
        when(search.search(any())).thenReturn(new SearchResult(List.of()));
        var service =
                new QuestionService(
                        search,
                        request -> {
                            throw new AssertionError("No chat expected");
                        },
                        telemetry());
        service.generate(new SearchQuery(PRIVATE, SearchScope.all()));
        service.generate(new SearchQuery(PRIVATE, SearchScope.selected(Set.of())));
        assertCounter("secondbrain.question.requests", "no_evidence", 1);
        assertCounter("secondbrain.question.requests", "empty_scope", 1);
        assertThat(meters.get("secondbrain.question.duration").timers()).hasSize(2);
        assertCounter("secondbrain.chat.requests", "candidate_answer", 0);
        assertThat(meters.find("secondbrain.chat.request.duration").timer()).isNull();
        assertThat(meters.get("secondbrain.chat.usage.reports").counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @ParameterizedTest
    @EnumSource(QuestionFailure.Reason.class)
    void requestFailuresAreDistinctAndDoNotInventProviderActivity(QuestionFailure.Reason reason) {
        try (var operation = telemetry().startQuestion()) {
            operation.failure(reason);
        }
        var tag = reason.name().toLowerCase(Locale.ROOT);
        assertCounter("secondbrain.question.requests", tag, 1);
        assertThat(meters.get("secondbrain.question.duration").tag("outcome", tag).timer().count())
                .isEqualTo(1);
        assertThat(meters.get("secondbrain.chat.requests").counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @ParameterizedTest
    @EnumSource(ChatFailure.Reason.class)
    void providerFailuresHaveDistinctOutcomesAndNoUsageEvenIfMetadataWasSet(
            ChatFailure.Reason reason) {
        try (var operation = telemetry().startProvider()) {
            operation.usage(OptionalLong.of(19), OptionalLong.of(7), OptionalLong.of(26));
            operation.failure(reason);
        }
        var tag = reason.name().toLowerCase(Locale.ROOT);
        assertCounter("secondbrain.chat.requests", tag, 1);
        assertThat(
                        meters.get("secondbrain.chat.request.duration")
                                .tag("outcome", tag)
                                .timer()
                                .count())
                .isEqualTo(1);
        assertThat(meters.get("secondbrain.chat.usage.reports").counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @ParameterizedTest
    @ValueSource(strings = {"answer", "insufficient", "search_error", "chat_error"})
    void composedSuccessAndPrivateExceptionsKeepTraceCorrelationWithoutCapturingContent(
            String scenario, CapturedOutput output) {
        var telemetry = telemetry();
        var capture = InMemorySpanExporter.create();
        var source = source();
        var search = mock(SearchService.class);
        if (scenario.equals("search_error"))
            when(search.search(any())).thenThrow(new IllegalStateException(PRIVATE));
        else
            when(search.search(any()))
                    .thenAnswer(
                            call -> {
                                clock.add(Duration.ofMillis(75));
                                return new SearchResult(List.of(source));
                            });
        ChatProvider fake =
                request -> {
                    try (var operation = telemetry.startProvider()) {
                        clock.add(Duration.ofMillis(125));
                        if (scenario.equals("chat_error")) {
                            operation.failure(ChatFailure.Reason.QUOTA_EXHAUSTED);
                            throw new ChatFailure(
                                    ChatFailure.Reason.QUOTA_EXHAUSTED,
                                    null,
                                    new RuntimeException(PRIVATE));
                        }
                        var outcome =
                                scenario.equals("insufficient")
                                        ? ChatProvider.Outcome.INSUFFICIENT_INFORMATION
                                        : ChatProvider.Outcome.ANSWERED;
                        operation.candidate(outcome);
                        operation.usage(
                                OptionalLong.of(19), OptionalLong.of(7), OptionalLong.of(26));
                        return new ChatProvider.Reply(
                                outcome,
                                outcome == ChatProvider.Outcome.ANSWERED ? PRIVATE : "",
                                outcome == ChatProvider.Outcome.ANSWERED
                                        ? List.of(source.passageId())
                                        : List.of());
                    }
                };
        var service = new QuestionService(search, fake, telemetry);
        try (var sdk =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(capture))
                        .build()) {
            var tracer =
                    new OtelTracer(sdk.get("test"), new OtelCurrentTraceContext(), event -> {});
            observations
                    .observationConfig()
                    .observationHandler(new DefaultTracingObservationHandler(tracer));
            var caller = tracer.nextSpan().name("caller").start();
            var scope = tracer.withSpan(caller);
            try (scope) {
                if (scenario.endsWith("error"))
                    assertThatThrownBy(
                                    () ->
                                            service.generate(
                                                    new SearchQuery(PRIVATE, SearchScope.all())))
                            .isInstanceOf(QuestionFailure.class)
                            .hasMessageNotContaining(PRIVATE);
                else
                    assertThat(
                                    service.generate(new SearchQuery(PRIVATE, SearchScope.all()))
                                            .reply()
                                            .outcome())
                            .isEqualTo(
                                    scenario.equals("answer")
                                            ? ChatProvider.Outcome.ANSWERED
                                            : ChatProvider.Outcome.INSUFFICIENT_INFORMATION);
                assertThat(tracer.currentSpan().context().spanId())
                        .isEqualTo(caller.context().spanId());
                assertThat(observations.getCurrentObservation()).isNull();
            } finally {
                caller.end();
            }
            var operationSpans =
                    capture.getFinishedSpanItems().stream()
                            .filter(span -> !span.getName().equals("caller"))
                            .toList();
            assertThat(operationSpans).hasSize(scenario.equals("search_error") ? 1 : 2);
            for (var span : operationSpans) {
                assertThat(span.getTraceId()).isEqualTo(caller.context().traceId());
                assertThat(span.getParentSpanId()).isEqualTo(caller.context().spanId());
                assertThat(span.getName()).isIn("question.generate", "chat.request");
                assertThat(span.getEvents()).isEmpty();
                assertThat(span.getAttributes().size()).isEqualTo(1);
                assertThat(span.getAttributes().get(AttributeKey.stringKey("outcome")))
                        .isNotBlank();
                assertThat(span.getAttributes().toString() + span.getName() + span.getStatus())
                        .doesNotContain(PRIVATE);
            }
        }
        var outcome =
                switch (scenario) {
                    case "answer" -> "candidate_answer";
                    case "insufficient" -> "insufficient_information";
                    case "chat_error" -> "quota_exhausted";
                    default -> "unavailable";
                };
        assertCounter("secondbrain.question.requests", outcome, 1);
        if (!scenario.equals("search_error")) {
            assertCounter("secondbrain.chat.requests", outcome, 1);
            assertThat(
                            meters.get("secondbrain.question.duration")
                                    .tag("outcome", outcome)
                                    .timer()
                                    .totalTime(TimeUnit.MILLISECONDS))
                    .isEqualTo(200);
            assertThat(
                            meters.get("secondbrain.chat.request.duration")
                                    .tag("outcome", outcome)
                                    .timer()
                                    .totalTime(TimeUnit.MILLISECONDS))
                    .isEqualTo(125);
        }
        assertSafeMeters();
        assertThat(output)
                .contains("Question operation finished")
                .doesNotContain(
                        PRIVATE,
                        source.passageId().toString(),
                        source.documentId().toString(),
                        "Exception");
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "stop", "meters"})
    void failingDiagnosticsCannotReplaceTheCandidateOrTheSafeFailure(
            String phase, CapturedOutput output) {
        var broken = mock(MeterRegistry.class);
        when(broken.counter(any(String.class), any(String[].class)))
                .thenThrow(new IllegalStateException(PRIVATE));
        observations
                .observationConfig()
                .observationHandler(
                        new ObservationHandler<Observation.Context>() {
                            @Override
                            public boolean supportsContext(Observation.Context context) {
                                return true;
                            }

                            @Override
                            public void onStart(Observation.Context context) {
                                if (phase.equals("start")) throw new IllegalStateException(PRIVATE);
                            }

                            @Override
                            public void onStop(Observation.Context context) {
                                if (phase.equals("stop")) throw new IllegalStateException(PRIVATE);
                            }
                        });
        var telemetry = new QuestionTelemetry(broken, observations);
        var search = mock(SearchService.class);
        var source = source();
        when(search.search(any())).thenReturn(new SearchResult(List.of(source)));
        var reply =
                new ChatProvider.Reply(
                        ChatProvider.Outcome.ANSWERED, PRIVATE, List.of(source.passageId()));
        var service = new QuestionService(search, request -> reply, telemetry);
        assertThat(service.generate(new SearchQuery(PRIVATE, SearchScope.all())).reply())
                .isSameAs(reply);
        var failed =
                new QuestionService(
                        search,
                        request -> {
                            throw new ChatFailure(
                                    ChatFailure.Reason.QUOTA_EXHAUSTED,
                                    null,
                                    new RuntimeException(PRIVATE));
                        },
                        telemetry);
        assertThatThrownBy(() -> failed.generate(new SearchQuery(PRIVATE, SearchScope.all())))
                .isInstanceOfSatisfying(
                        QuestionFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(QuestionFailure.Reason.QUOTA_EXHAUSTED));
        assertThat(observations.getCurrentObservation()).isNull();
        assertThat(output).contains("Question telemetry unavailable").doesNotContain(PRIVATE);
    }

    @Test
    void realAdapterFailureAndInputGuardsAreInstrumentedWithoutCredentialsOrASocket(
            CapturedOutput output) throws Exception {
        var telemetry = telemetry();
        var provider =
                new CloudflareChatProvider(
                        CloudflareChatProtocolTest.settings(Duration.ofSeconds(2)),
                        CloudflareChatProtocolTest.CLOCK,
                        URI.create("http://127.0.0.1:1/chat"),
                        telemetry);
        provider.close();
        assertThatThrownBy(() -> provider.generate(CloudflareChatProtocolTest.input()))
                .isInstanceOf(ChatFailure.class);
        assertCounter("secondbrain.chat.requests", "unavailable", 1);
        assertThatThrownBy(() -> provider.generate(null)).isInstanceOf(ChatFailure.class);
        assertCounter("secondbrain.chat.requests", "invalid_input", 1);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> provider.generate(CloudflareChatProtocolTest.input()))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertCounter("secondbrain.chat.requests", "interrupted", 1);
        } finally {
            Thread.interrupted();
        }
        assertThat(output)
                .doesNotContain(
                        "synthetic-chat-token",
                        "private-question",
                        "private-passage",
                        "127.0.0.1:1");
    }

    private void assertCounter(String name, String outcome, double count) {
        assertThat(meters.get(name).tag("outcome", outcome).counter().count()).isEqualTo(count);
    }

    private void assertSafeMeters() {
        for (var meter : meters.getMeters()) {
            assertThat(meter.getId().getTags())
                    .allSatisfy(tag -> assertThat(tag.getKey()).isIn("outcome", "kind", "error"));
            assertThat(meter.getId().toString()).doesNotContain(PRIVATE);
        }
    }

    private static SearchResult.Match source() {
        return new SearchResult.Match(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                1,
                PRIVATE,
                new SearchResult.SourceLocation(1, 0, 1, PRIVATE.length(), OptionalInt.empty()),
                0.9);
    }
}
