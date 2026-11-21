package dev.secondbrain.summaries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.secondbrain.events.DeliveryFailure;
import dev.secondbrain.events.EventHandler;
import dev.secondbrain.questions.ChatFailure;
import dev.secondbrain.questions.ChatProvider;
import dev.secondbrain.search.SearchResult;
import dev.secondbrain.search.SearchService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

class SummaryGeneratorTest {
    private static final Instant AT = Instant.parse("2026-11-21T12:00:00Z");
    private final SearchService search = mock(SearchService.class);
    private final ChatProvider chat = mock(ChatProvider.class);
    private final JdbcSummaryRepository summaries = mock(JdbcSummaryRepository.class);
    private final SummaryGenerator generator =
            new SummaryGenerator(
                    search, chat, summaries, Clock.fixed(AT, ZoneOffset.UTC), Optional.empty());
    private final UUID document = UUID.randomUUID();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    @Test
    void aDocumentWithoutAnActiveIndexIsSkippedWithoutAProviderCall() throws Exception {
        when(search.representative(document, 5)).thenReturn(new SearchResult(List.of()));
        assertThat(generator.prepare(document).apply()).isEqualTo(EventHandler.Outcome.SKIPPED);
        verifyNoInteractions(chat, summaries);
    }

    @Test
    void storesACitedSummaryForTheGenerationItWasBuiltFrom() throws Exception {
        passages(match(first, "ArrayList keeps order."), match(second, "HashSet is unique."));
        when(chat.generate(any()))
                .thenReturn(
                        new ChatProvider.Reply(
                                ChatProvider.Outcome.ANSWERED,
                                " Lists keep order [1]. Sets are unique [2]. Bogus [7]. ",
                                List.of(first, second)));
        when(summaries.save(eq(document), eq(3L), any(), any(), eq(AT))).thenReturn(true);
        assertThat(generator.prepare(document).apply()).isEqualTo(EventHandler.Outcome.COMPLETED);
        verify(summaries)
                .save(
                        document,
                        3,
                        "Lists keep order [1]. Sets are unique [2]. Bogus .",
                        List.of(first, second),
                        AT);
    }

    @Test
    void staleWorkIsSkippedWhenTheGenerationIsNoLongerActive() throws Exception {
        passages(match(first, "Text."));
        when(chat.generate(any()))
                .thenReturn(
                        new ChatProvider.Reply(
                                ChatProvider.Outcome.ANSWERED, "Text [1].", List.of(first)));
        when(summaries.save(any(), anyLong(), any(), any(), any())).thenReturn(false);
        assertThat(generator.prepare(document).apply()).isEqualTo(EventHandler.Outcome.SKIPPED);
    }

    @Test
    void citationsOutsideTheSuppliedPassagesAreRejected() throws Exception {
        passages(match(first, "Text."));
        when(chat.generate(any()))
                .thenReturn(
                        new ChatProvider.Reply(
                                ChatProvider.Outcome.ANSWERED,
                                "Text [1].",
                                List.of(UUID.randomUUID())));
        assertThatThrownBy(() -> generator.prepare(document)).isInstanceOf(DeliveryFailure.class);
        verify(summaries, never()).save(any(), anyLong(), any(), any(), any());
    }

    @Test
    void insufficientEvidenceStoresNothing() throws Exception {
        passages(match(first, "Text."));
        when(chat.generate(any()))
                .thenReturn(
                        new ChatProvider.Reply(
                                ChatProvider.Outcome.INSUFFICIENT_INFORMATION, "", List.of()));
        assertThat(generator.prepare(document).apply()).isEqualTo(EventHandler.Outcome.SKIPPED);
        verify(summaries, never()).save(any(), anyLong(), any(), any(), any());
    }

    @Test
    void quotaFailuresBecomeDeliveryFailuresWithTheProviderRetryHint() throws Exception {
        passages(match(first, "Text."));
        var failure = mock(ChatFailure.class);
        when(failure.reason()).thenReturn(ChatFailure.Reason.QUOTA_EXHAUSTED);
        when(failure.retryAt()).thenReturn(Optional.of(AT.plusSeconds(60)));
        when(chat.generate(any())).thenThrow(failure);
        assertThatThrownBy(() -> generator.prepare(document))
                .isInstanceOf(DeliveryFailure.class)
                .hasCause(failure);
    }

    @ParameterizedTest
    @EnumSource(ChatFailure.Reason.class)
    void onlyQuotaRateLimitOutageAndTimeoutAreRetried(ChatFailure.Reason reason) {
        assertThat(SummaryGenerator.temporary(reason))
                .isEqualTo(
                        switch (reason) {
                            case RATE_LIMITED, QUOTA_EXHAUSTED, UNAVAILABLE, TIMED_OUT -> true;
                            default -> false;
                        });
    }

    @Test
    void sendsOnlyAsManyPassagesAsFitTheRequestBudget() throws Exception {
        var long1 = match(first, "é".repeat(1200));
        var long2 = match(second, "é".repeat(1200));
        var third = UUID.randomUUID();
        var long3 = match(third, "é".repeat(1200));
        // A fourth 2,400-byte passage would exceed 8,000 bytes; the short one still fits.
        var tooMuch = match(UUID.randomUUID(), "é".repeat(1200));
        var fourth = UUID.randomUUID();
        passages(long1, long2, long3, tooMuch, match(fourth, "Short."));
        var requests = new ArrayList<ChatProvider.Request>();
        when(chat.generate(any()))
                .thenAnswer(
                        call -> {
                            requests.add(call.getArgument(0));
                            return new ChatProvider.Reply(
                                    ChatProvider.Outcome.INSUFFICIENT_INFORMATION, "", List.of());
                        });
        generator.prepare(document);
        var request = requests.getFirst();
        int bytes = request.question().getBytes(StandardCharsets.UTF_8).length;
        for (var passage : request.evidence())
            bytes += passage.text().getBytes(StandardCharsets.UTF_8).length;
        assertThat(bytes).isLessThanOrEqualTo(ChatProvider.Request.MAXIMUM_INPUT_BYTES);
        assertThat(request.evidence())
                .extracting(ChatProvider.Passage::passageId)
                .containsExactly(first, second, third, fourth);
    }

    private void passages(SearchResult.Match... matches) {
        when(search.representative(document, 5)).thenReturn(new SearchResult(List.of(matches)));
    }

    private SearchResult.Match match(UUID passage, String text) {
        return new SearchResult.Match(
                passage,
                document,
                3,
                1,
                text,
                new SearchResult.SourceLocation(1, 0, 1, 10, OptionalInt.empty()),
                0.9);
    }
}
