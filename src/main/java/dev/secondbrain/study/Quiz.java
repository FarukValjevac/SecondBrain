package dev.secondbrain.study;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A practice quiz; questions keep the user's answer and its grade once submitted. */
public record Quiz(UUID quizId, Instant createdAt, List<Question> questions) {
    public Quiz {
        Objects.requireNonNull(quizId, "quizId");
        Objects.requireNonNull(createdAt, "createdAt");
        questions = List.copyOf(questions);
    }

    public boolean graded() {
        return questions.stream().allMatch(question -> question.verdict().isPresent());
    }

    /** One question tied to the passage it was written from. */
    public record Question(
            int position,
            UUID documentId,
            UUID passageId,
            String text,
            Optional<String> answer,
            Optional<QuizParser.Verdict> verdict,
            Optional<String> explanation) {
        public Question {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(passageId, "passageId");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(answer, "answer");
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(explanation, "explanation");
        }

        @Override
        public String toString() {
            return "Question[position=" + position + "]";
        }
    }

    @Override
    public String toString() {
        // Questions and answers are private.
        return "Quiz[quizId=" + quizId + ", questions=" + questions.size() + "]";
    }
}
