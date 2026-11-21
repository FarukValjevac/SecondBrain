package dev.secondbrain.study;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.study.ReviewScheduler.Grade;
import dev.secondbrain.study.ReviewScheduler.Schedule;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

class ReviewSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    @Test
    void goodRecallFollowsOneSixThenEaseGrowth() {
        var schedule = Schedule.initial(NOW);
        assertThat(schedule.dueAt()).isEqualTo(NOW);
        schedule = ReviewScheduler.next(schedule, Grade.GOOD, NOW);
        assertThat(schedule.intervalDays()).isEqualTo(1);
        assertThat(schedule.dueAt()).isEqualTo(NOW.plus(Duration.ofDays(1)));
        schedule = ReviewScheduler.next(schedule, Grade.GOOD, NOW);
        assertThat(schedule.intervalDays()).isEqualTo(6);
        schedule = ReviewScheduler.next(schedule, Grade.GOOD, NOW);
        assertThat(schedule.intervalDays()).isEqualTo(15);
        assertThat(schedule.repetitions()).isEqualTo(3);
    }

    @Test
    void forgettingResetsProgressAndLowersEaseButNotBelowTheMinimum() {
        var learned = new Schedule(40, 1.35, 5, NOW);
        var again = ReviewScheduler.next(learned, Grade.AGAIN, NOW);
        assertThat(again.intervalDays()).isZero();
        assertThat(again.repetitions()).isZero();
        assertThat(again.ease()).isEqualTo(ReviewScheduler.MINIMUM_EASE);
        assertThat(again.dueAt()).isEqualTo(NOW.plus(ReviewScheduler.RELEARN_DELAY));
    }

    @Test
    void hardGrowsSlowlyAndEasyGrowsFaster() {
        var learned = new Schedule(10, 2.5, 3, NOW);
        assertThat(ReviewScheduler.next(learned, Grade.HARD, NOW).intervalDays()).isEqualTo(12);
        assertThat(ReviewScheduler.next(learned, Grade.EASY, NOW).intervalDays()).isEqualTo(34);
        assertThat(ReviewScheduler.next(learned, Grade.EASY, NOW).ease()).isEqualTo(2.65);
    }

    @Test
    void intervalsAlwaysGrowAndStayBounded() {
        var learned = new Schedule(3600, 3.0, 20, NOW);
        assertThat(ReviewScheduler.next(learned, Grade.EASY, NOW).intervalDays()).isEqualTo(3650);
        var small = new Schedule(1, 1.3, 2, NOW);
        assertThat(ReviewScheduler.next(small, Grade.HARD, NOW).intervalDays()).isEqualTo(2);
    }
}
