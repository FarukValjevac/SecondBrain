package dev.secondbrain.questions;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A user's rating of one answer, with the question, answer text and cited passages it rated. */
public record AnswerFeedback(
        UUID feedbackId,
        boolean helpful,
        Optional<Reason> reason,
        String question,
        String answer,
        List<Source> sources,
        Instant createdAt) {
    public static final int MAXIMUM_ANSWER_LENGTH = 20_000;

    public AnswerFeedback {
        Objects.requireNonNull(feedbackId, "feedbackId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(createdAt, "createdAt");
        sources = List.copyOf(sources);
        if (helpful && reason.isPresent())
            throw new IllegalArgumentException("A helpful rating has no reason");
        if (question.isBlank() || question.codePointCount(0, question.length()) > 1200)
            throw new IllegalArgumentException("Invalid feedback question");
        if (answer.isBlank() || answer.length() > MAXIMUM_ANSWER_LENGTH)
            throw new IllegalArgumentException("Invalid feedback answer");
        if (sources.isEmpty() || sources.size() > 5)
            throw new IllegalArgumentException("Feedback needs one to five sources");
    }

    public enum Reason {
        WRONG,
        INCOMPLETE,
        WRONG_SOURCE
    }

    /** A cited passage, identified only by stable IDs. */
    public record Source(UUID documentId, UUID passageId) {
        public Source {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(passageId, "passageId");
        }
    }

    @Override
    public String toString() {
        // Question and answer text are private.
        return "AnswerFeedback[feedbackId=" + feedbackId + ", helpful=" + helpful + "]";
    }
}
