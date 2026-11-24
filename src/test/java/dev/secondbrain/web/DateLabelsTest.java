package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

class DateLabelsTest {
    private final DateLabels labels =
            new DateLabels(
                    Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneId.of("Europe/Berlin")));

    @Test
    void readsLikeAPersonWouldSayIt() {
        assertThat(labels.label(Instant.parse("2026-11-24T08:05:00Z"))).isEqualTo("Today, 09:05");
        assertThat(labels.label(Instant.parse("2026-11-23T13:30:00Z")))
                .isEqualTo("Yesterday, 14:30");
        assertThat(labels.label(Instant.parse("2026-03-02T11:00:00Z"))).isEqualTo("2 Mar, 12:00");
        assertThat(labels.label(Instant.parse("2025-11-22T11:00:00Z"))).isEqualTo("22 Nov 2025");
        assertThat(labels.label(null)).isEmpty();
    }

    @Test
    void usesTheLocalCalendarDayNotUtc() {
        // 23:30 UTC on the 23rd is already the 24th in Berlin.
        assertThat(labels.label(Instant.parse("2026-11-23T23:30:00Z"))).isEqualTo("Today, 00:30");
    }
}
