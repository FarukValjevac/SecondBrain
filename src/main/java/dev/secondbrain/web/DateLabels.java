package dev.secondbrain.web;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Short, readable dates for lists: "Today, 14:30", "Yesterday, 09:05", "22 Nov, 14:30" this year,
 * "22 Nov 2025" before. Pages keep the exact instant in each {@code <time datetime>} attribute.
 * Public because Thymeleaf expressions call it.
 */
public final class DateLabels {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter OLDER =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private final Clock clock;

    DateLabels(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String label(Instant instant) {
        if (instant == null) return "";
        var zone = clock.getZone();
        var moment = instant.atZone(zone);
        var today = LocalDate.now(clock);
        var day = moment.toLocalDate();
        if (day.equals(today)) return "Today, " + TIME.format(moment);
        if (day.equals(today.minusDays(1))) return "Yesterday, " + TIME.format(moment);
        if (day.getYear() == today.getYear()) return DAY.format(moment);
        return OLDER.format(moment);
    }
}
