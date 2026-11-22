package dev.secondbrain.conversations;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A saved Ask conversation. Titles, questions and answers are user data: never log them. */
public record Conversation(UUID conversationId, String title, Instant updatedAt, List<Turn> turns) {
    public Conversation {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(updatedAt, "updatedAt");
        turns = List.copyOf(turns);
    }

    /** Scope of the latest turn, so a reopened conversation continues where it was asked. */
    public String lastScope() {
        return turns.isEmpty() ? "all" : turns.getLast().scope();
    }

    @Override
    public String toString() {
        return "Conversation[id=" + conversationId + ", turns=" + turns.size() + "]";
    }

    /** One list entry: no questions or answers. */
    public record Summary(UUID conversationId, String title, Instant updatedAt) {
        public Summary {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }

        @Override
        public String toString() {
            return "ConversationSummary[id=" + conversationId + "]";
        }
    }

    public enum Outcome {
        ANSWERED,
        INSUFFICIENT
    }

    /** A cited passage, by reference only. */
    public record SourceRef(UUID documentId, UUID passageId) {
        public SourceRef {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(passageId, "passageId");
        }
    }

    public record Turn(
            int position,
            String question,
            String scope,
            Outcome outcome,
            String answer,
            List<SourceRef> sources,
            Instant createdAt) {
        public Turn {
            Objects.requireNonNull(question, "question");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(answer, "answer");
            Objects.requireNonNull(createdAt, "createdAt");
            sources = List.copyOf(sources);
            if (position < 1 || sources.size() > 5)
                throw new IllegalArgumentException("Invalid conversation turn");
        }

        @Override
        public String toString() {
            return "Turn[position=" + position + ", outcome=" + outcome + "]";
        }
    }
}
