package dev.secondbrain.study;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict readers for generated quiz questions and grading replies; anything else is dropped. */
public final class QuizParser {
    static final int MAXIMUM_QUESTIONS = 5;
    static final int MAXIMUM_QUESTION_LENGTH = 300;
    static final int MAXIMUM_EXPLANATION_LENGTH = 600;
    private static final Pattern QUESTION =
            Pattern.compile("^\\s*Q:\\s*(.+?)\\s*\\[(\\d{1,2})\\]\\s*$");
    private static final Pattern VERDICT =
            Pattern.compile(
                    "^\\s*VERDICT:\\s*(CORRECT|PARTLY_CORRECT|INCORRECT)\\s*(?:[-:]\\s*)?(.*)$",
                    Pattern.DOTALL);

    private QuizParser() {}

    record Question(String text, UUID passageId) {}

    /** Grading result for one answer. */
    public enum Verdict {
        CORRECT,
        PARTLY_CORRECT,
        INCORRECT,
        /** The cited source could not decide; never counted as correct. */
        UNGRADED
    }

    record Grade(Verdict verdict, String explanation) {}

    static List<Question> questions(String text, List<UUID> citations) {
        var questions = new ArrayList<Question>();
        var seen = new HashSet<String>();
        for (var line : text.split("\\R")) {
            if (questions.size() == MAXIMUM_QUESTIONS) break;
            var matcher = QUESTION.matcher(line);
            if (!matcher.matches()) continue;
            var question = matcher.group(1).strip();
            int number = Integer.parseInt(matcher.group(2));
            if (number < 1
                    || number > citations.size()
                    || question.length() > MAXIMUM_QUESTION_LENGTH
                    || !seen.add(question.toLowerCase(Locale.ROOT))) continue;
            questions.add(new Question(question, citations.get(number - 1)));
        }
        return List.copyOf(questions);
    }

    /** Reads only the first line's verdict; a missing or unknown verdict is ungraded. */
    static Grade grade(String reply) {
        var lines = reply.strip().split("\\R", 2);
        var matcher = VERDICT.matcher(lines[0]);
        if (!matcher.matches()) return new Grade(Verdict.UNGRADED, "");
        var explanation = (matcher.group(2) + (lines.length > 1 ? " " + lines[1] : "")).strip();
        if (explanation.length() > MAXIMUM_EXPLANATION_LENGTH)
            explanation = explanation.substring(0, MAXIMUM_EXPLANATION_LENGTH);
        return new Grade(Verdict.valueOf(matcher.group(1)), explanation);
    }

    static Optional<Verdict> verdict(String stored) {
        return stored == null ? Optional.empty() : Optional.of(Verdict.valueOf(stored));
    }
}
