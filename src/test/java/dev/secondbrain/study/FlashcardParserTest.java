package dev.secondbrain.study;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class FlashcardParserTest {
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    @Test
    void readsWellFormedCardsAndMapsEachToItsCitedPassage() {
        var cards =
                FlashcardParser.parse(
                        """
                        Here are your cards:
                        Q: Does ArrayList allow duplicates? || A: Yes, it permits them. [1]
                          Q: How does HashSet store values?||A: Each distinct value once. [2]
                        """,
                        List.of(first, second));
        assertThat(cards)
                .containsExactly(
                        new FlashcardParser.Card(
                                "Does ArrayList allow duplicates?", "Yes, it permits them.", first),
                        new FlashcardParser.Card(
                                "How does HashSet store values?",
                                "Each distinct value once.",
                                second));
    }

    @Test
    void dropsMalformedUncitedOutOfRangeRepeatedAndOversizedCards() {
        var cards =
                FlashcardParser.parse(
                        """
                        Q: No citation || A: Missing marker.
                        Q: Out of range || A: Cites nothing supplied. [3]
                        Q: Zero || A: Invalid. [0]
                        A: Answer first || Q: Wrong order [1]
                        Q: Kept || A: Valid. [1]
                        Q: kept || A: Same question again. [1]
                        Q: Long || A: %s [1]
                        """
                                .formatted("x".repeat(601)),
                        List.of(first, second));
        assertThat(cards).extracting(FlashcardParser.Card::question).containsExactly("Kept");
    }

    @Test
    void keepsAtMostTheCardLimit() {
        var text = new StringBuilder();
        for (int i = 0; i < 12; i++)
            text.append("Q: Question ").append(i).append(" || A: Yes. [1]\n");
        assertThat(FlashcardParser.parse(text.toString(), List.of(first)))
                .hasSize(FlashcardParser.MAXIMUM_CARDS);
    }
}
