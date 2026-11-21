package dev.secondbrain.study;

import java.util.Objects;
import java.util.UUID;

/** One stored study card and its review state. */
public record Flashcard(
        UUID cardId,
        UUID documentId,
        UUID passageId,
        String question,
        String answer,
        ReviewScheduler.Schedule schedule) {
    public Flashcard {
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(passageId, "passageId");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(schedule, "schedule");
    }

    @Override
    public String toString() {
        // Card text comes from private documents.
        return "Flashcard[cardId=" + cardId + "]";
    }
}
