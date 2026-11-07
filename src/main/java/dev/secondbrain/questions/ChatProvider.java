package dev.secondbrain.questions;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Provider boundary only; replies require local citation/visibility validation before display. */
public interface ChatProvider {
    /**
     * One attempt outside database transactions; interruption must remain visible to the caller.
     */
    Reply generate(Request request) throws InterruptedException;

    /** Evidence is reference data, never trusted instructions or executable tools. */
    record Passage(UUID passageId, String text) {
        public static final int MAXIMUM_CODE_POINTS = 1200;

        public Passage {
            Objects.requireNonNull(passageId, "passageId");
            requireText(text, MAXIMUM_CODE_POINTS);
        }

        @Override
        public String toString() {
            return "Passage[redacted]";
        }
    }

    /** Hard text limits, separate from the evidence-selection policy and provider token budget. */
    record Request(String question, List<Passage> evidence) {
        public static final int MAXIMUM_QUESTION_CODE_POINTS = 1200;
        public static final int MAXIMUM_PASSAGES = 5;
        public static final int MAXIMUM_INPUT_BYTES = 8000;

        public Request {
            requireText(question, MAXIMUM_QUESTION_CODE_POINTS);
            question = question.strip();
            evidence = List.copyOf(evidence);
            if (evidence.isEmpty() || evidence.size() > MAXIMUM_PASSAGES)
                throw new IllegalArgumentException("Chat request requires bounded evidence");
            var ids = new HashSet<UUID>();
            int bytes = question.getBytes(StandardCharsets.UTF_8).length;
            for (var passage : evidence) {
                if (!ids.add(passage.passageId()))
                    throw new IllegalArgumentException("Chat request contains duplicate passages");
                bytes += passage.text().getBytes(StandardCharsets.UTF_8).length;
            }
            if (bytes > MAXIMUM_INPUT_BYTES)
                throw new IllegalArgumentException("Chat input exceeds the text byte budget");
        }

        @Override
        public String toString() {
            return "ChatRequest[passages=" + evidence.size() + "]";
        }
    }

    enum Outcome {
        ANSWERED,
        INSUFFICIENT_INFORMATION
    }

    /**
     * Untrusted model candidate, not an accepted sourced answer. Citation duplicates remain
     * explicit.
     */
    record Reply(Outcome outcome, String answer, List<UUID> citations) {
        public static final int MAXIMUM_ANSWER_CODE_POINTS = 8000;
        public static final int MAXIMUM_CITATIONS = 20;

        public Reply {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(answer, "answer");
            citations = List.copyOf(citations);
            if (citations.size() > MAXIMUM_CITATIONS)
                throw new IllegalArgumentException("Too many model citation candidates");
            if (outcome == Outcome.ANSWERED) {
                requireText(answer, MAXIMUM_ANSWER_CODE_POINTS);
            } else if (!answer.isEmpty() || !citations.isEmpty()) {
                throw new IllegalArgumentException(
                        "Insufficient information cannot contain an answer or citations");
            }
        }

        @Override
        public String toString() {
            return "ChatReply[outcome=" + outcome + ", citations=" + citations.size() + "]";
        }
    }

    private static void requireText(String text, int maximumCodePoints) {
        Objects.requireNonNull(text, "text");
        if (text.length() > maximumCodePoints * 2
                || text.isBlank()
                || text.codePointCount(0, text.length()) > maximumCodePoints
                || text.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(text))
            throw new IllegalArgumentException("Invalid or oversized chat text");
    }
}
