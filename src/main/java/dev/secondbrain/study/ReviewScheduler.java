package dev.secondbrain.study;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A small SM-2 style scheduler. Good recall grows the interval by the card's ease; forgetting
 * resets it. Pure and deterministic: the caller supplies the current time.
 */
public final class ReviewScheduler {
    static final double MINIMUM_EASE = 1.3;
    static final double INITIAL_EASE = 2.5;
    static final Duration RELEARN_DELAY = Duration.ofMinutes(10);

    private ReviewScheduler() {}

    /** How well the user recalled the answer. */
    public enum Grade {
        AGAIN,
        HARD,
        GOOD,
        EASY
    }

    /** Review state of one card. */
    public record Schedule(int intervalDays, double ease, int repetitions, Instant dueAt) {
        public Schedule {
            Objects.requireNonNull(dueAt, "dueAt");
            if (intervalDays < 0 || repetitions < 0 || ease < MINIMUM_EASE)
                throw new IllegalArgumentException("Invalid review schedule");
        }

        /** A new card is due immediately. */
        public static Schedule initial(Instant now) {
            return new Schedule(0, INITIAL_EASE, 0, now);
        }
    }

    public static Schedule next(Schedule current, Grade grade, Instant now) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(grade, "grade");
        Objects.requireNonNull(now, "now");
        if (grade == Grade.AGAIN)
            return new Schedule(
                    0, Math.max(MINIMUM_EASE, current.ease() - 0.2), 0, now.plus(RELEARN_DELAY));
        double ease =
                switch (grade) {
                    case HARD -> Math.max(MINIMUM_EASE, current.ease() - 0.15);
                    case EASY -> current.ease() + 0.15;
                    default -> current.ease();
                };
        int interval =
                switch (current.repetitions()) {
                    case 0 -> grade == Grade.EASY ? 3 : 1;
                    case 1 -> grade == Grade.HARD ? 3 : grade == Grade.EASY ? 8 : 6;
                    default -> {
                        double factor =
                                switch (grade) {
                                    case HARD -> 1.2;
                                    case EASY -> ease * 1.3;
                                    default -> ease;
                                };
                        // Cap at about ten years so the date stays far from overflow.
                        yield (int)
                                Math.min(
                                        3650,
                                        Math.max(
                                                current.intervalDays() + 1,
                                                Math.round(current.intervalDays() * factor)));
                    }
                };
        return new Schedule(
                interval, ease, current.repetitions() + 1, now.plus(Duration.ofDays(interval)));
    }
}
