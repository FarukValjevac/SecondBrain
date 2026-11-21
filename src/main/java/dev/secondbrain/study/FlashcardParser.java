package dev.secondbrain.study;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads generated cards in the fixed line format {@code Q: question || A: answer [n]}. A line that
 * does not match, is too long, repeats a question or cites no accepted passage is dropped.
 */
final class FlashcardParser {
    static final int MAXIMUM_CARDS = 8;
    static final int MAXIMUM_QUESTION_LENGTH = 300;
    static final int MAXIMUM_ANSWER_LENGTH = 600;
    private static final Pattern CARD =
            Pattern.compile("^\\s*Q:\\s*(.+?)\\s*\\|\\|\\s*A:\\s*(.+?)\\s*\\[(\\d{1,2})\\]\\s*$");

    private FlashcardParser() {}

    record Card(String question, String answer, UUID passageId) {}

    static List<Card> parse(String text, List<UUID> citations) {
        var cards = new ArrayList<Card>();
        var questions = new HashSet<String>();
        for (var line : text.split("\\R")) {
            if (cards.size() == MAXIMUM_CARDS) break;
            var matcher = CARD.matcher(line);
            if (!matcher.matches()) continue;
            var question = matcher.group(1).strip();
            var answer = matcher.group(2).strip();
            int number = Integer.parseInt(matcher.group(3));
            if (number < 1
                    || number > citations.size()
                    || question.length() > MAXIMUM_QUESTION_LENGTH
                    || answer.length() > MAXIMUM_ANSWER_LENGTH
                    || !questions.add(question.toLowerCase(java.util.Locale.ROOT))) continue;
            cards.add(new Card(question, answer, citations.get(number - 1)));
        }
        return List.copyOf(cards);
    }
}
