package dev.secondbrain.study;

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

import dev.secondbrain.questions.ChatFailure;
import dev.secondbrain.questions.ChatProvider;
import dev.secondbrain.search.SearchResult;
import dev.secondbrain.search.SearchService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

class FlashcardServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private final SearchService search = mock(SearchService.class);
    private final ChatProvider chat = mock(ChatProvider.class);
    private final JdbcFlashcardRepository cards = mock(JdbcFlashcardRepository.class);
    private final FlashcardService service =
            new FlashcardService(
                    search, chat, cards, Clock.fixed(NOW, ZoneOffset.UTC), Optional.empty());
    private final UUID document = UUID.randomUUID();
    private final UUID passage = UUID.randomUUID();

    @Test
    void aDocumentWithoutAnActiveIndexMakesNoProviderCall() throws Exception {
        when(search.representative(document, 5)).thenReturn(new SearchResult(List.of()));
        assertThat(service.generate(document)).isZero();
        verifyNoInteractions(chat, cards);
    }

    @Test
    void storesParsedCardsCitingSuppliedPassagesDueImmediately() throws Exception {
        ready();
        reply(
                "Q: Does ArrayList allow duplicates? || A: Yes. [1]\nQ: Broken line\n",
                List.of(passage));
        when(cards.insert(eq(document), eq(4L), any(), eq(NOW))).thenReturn(true);
        assertThat(service.generate(document)).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Flashcard>> stored = ArgumentCaptor.forClass(List.class);
        verify(cards).insert(eq(document), eq(4L), stored.capture(), eq(NOW));
        assertThat(stored.getValue())
                .singleElement()
                .satisfies(
                        card -> {
                            assertThat(card.passageId()).isEqualTo(passage);
                            assertThat(card.schedule().dueAt()).isEqualTo(NOW);
                        });
    }

    @Test
    void foreignCitationsOrNoUsableCardsStoreNothing() throws Exception {
        ready();
        reply("Q: A || A: B [1]", List.of(UUID.randomUUID()));
        assertThat(service.generate(document)).isZero();
        reply("No cards here.", List.of(passage));
        assertThat(service.generate(document)).isZero();
        verify(cards, never()).insert(any(), anyLong(), any(), any());
    }

    @Test
    void aDocumentDeletedDuringGenerationKeepsNoCards() throws Exception {
        ready();
        reply("Q: A || A: B [1]", List.of(passage));
        when(cards.insert(any(), anyLong(), any(), any())).thenReturn(false);
        assertThat(service.generate(document)).isZero();
    }

    @Test
    void providerFailuresReachTheCaller() throws Exception {
        ready();
        var failure = mock(ChatFailure.class);
        when(chat.generate(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.generate(document)).isSameAs(failure);
    }

    @Test
    void reviewingSchedulesFromTheStateTheUserSaw() {
        var card =
                new Flashcard(
                        UUID.randomUUID(),
                        document,
                        passage,
                        "Q",
                        "A",
                        ReviewScheduler.Schedule.initial(NOW));
        when(cards.find(card.cardId())).thenReturn(Optional.of(card));
        when(cards.reschedule(eq(card.cardId()), eq(card.schedule()), any())).thenReturn(true);
        assertThat(service.review(card.cardId(), ReviewScheduler.Grade.GOOD)).isTrue();
        verify(cards)
                .reschedule(
                        card.cardId(),
                        card.schedule(),
                        ReviewScheduler.next(card.schedule(), ReviewScheduler.Grade.GOOD, NOW));
        when(cards.find(any())).thenReturn(Optional.empty());
        assertThat(service.review(UUID.randomUUID(), ReviewScheduler.Grade.GOOD)).isFalse();
        verifyNoInteractions(chat);
    }

    private void ready() {
        when(search.representative(document, 5))
                .thenReturn(
                        new SearchResult(
                                List.of(
                                        new SearchResult.Match(
                                                passage,
                                                document,
                                                4,
                                                1,
                                                "ArrayList permits duplicates.",
                                                new SearchResult.SourceLocation(
                                                        1, 0, 1, 10, OptionalInt.empty()),
                                                0.9))));
    }

    private void reply(String text, List<UUID> citations) throws InterruptedException {
        when(chat.generate(any()))
                .thenReturn(new ChatProvider.Reply(ChatProvider.Outcome.ANSWERED, text, citations));
    }
}
