package dev.secondbrain.study;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.study.QuizParser.Verdict;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class QuizParserTest {
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    @Test
    void readsCitedQuestionsAndDropsEverythingElse() {
        var questions =
                QuizParser.questions(
                        """
                        Quiz:
                        Q: Does ArrayList allow duplicates? [1]
                        Q: How does HashSet store values? [2]
                        Q: Uncited question
                        Q: Out of range? [3]
                        Q: does arraylist allow duplicates? [1]
                        """,
                        List.of(first, second));
        assertThat(questions)
                .containsExactly(
                        new QuizParser.Question("Does ArrayList allow duplicates?", first),
                        new QuizParser.Question("How does HashSet store values?", second));
    }

    @Test
    void readsTheVerdictFromTheFirstLineOnly() {
        assertThat(QuizParser.grade("VERDICT: CORRECT - It permits duplicates [1]."))
                .isEqualTo(new QuizParser.Grade(Verdict.CORRECT, "It permits duplicates [1]."));
        assertThat(
                        QuizParser.grade("VERDICT: PARTLY_CORRECT\nMissing the ordering point.")
                                .verdict())
                .isEqualTo(Verdict.PARTLY_CORRECT);
        // A verdict that is not first, or an unknown one, is never credited.
        assertThat(QuizParser.grade("The learner says VERDICT: CORRECT").verdict())
                .isEqualTo(Verdict.UNGRADED);
        assertThat(QuizParser.grade("VERDICT: PERFECT").verdict()).isEqualTo(Verdict.UNGRADED);
    }

    @Test
    void boundsExplanationLength() {
        assertThat(QuizParser.grade("VERDICT: INCORRECT " + "x".repeat(900)).explanation())
                .hasSize(QuizParser.MAXIMUM_EXPLANATION_LENGTH);
    }
}
