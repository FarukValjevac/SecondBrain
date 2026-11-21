package dev.secondbrain.questions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Inline citation markers such as [2] refer to the answer's accepted citations by position. Only
 * markers that point to an accepted citation survive; invalid ones are removed, never shown.
 *
 * <p>A marker must follow whitespace, punctuation or another marker. Text such as {@code a[0]} or
 * {@code grid[0][1]} in an answer about code is an index, not a citation, and is left unchanged.
 */
public final class CitationMarkers {
    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,2})\\]");

    private CitationMarkers() {}

    /** A run of plain answer text, or one valid marker numbered from 1. */
    public record Segment(String text, Optional<Integer> marker) {}

    /** Removes markers outside 1..citations and repeats of the marker just before it. */
    static String retainValid(String answer, int citations) {
        var result = new StringBuilder(answer.length());
        int position = 0;
        int previousEnd = -1;
        int previousNumber = -1;
        var matcher = MARKER.matcher(answer);
        while (matcher.find()) {
            if (!markerPosition(answer, matcher.start(), previousEnd)) continue;
            result.append(answer, position, matcher.start());
            int number = Integer.parseInt(matcher.group(1));
            boolean repeated = previousEnd == matcher.start() && previousNumber == number;
            if (number >= 1 && number <= citations && !repeated) {
                result.append(matcher.group());
                previousNumber = number;
            }
            previousEnd = matcher.end();
            position = matcher.end();
        }
        return result.append(answer, position, answer.length()).toString();
    }

    /** Splits accepted answer text for rendering; anything not a valid marker stays plain text. */
    public static List<Segment> segments(String answer, int citations) {
        var segments = new ArrayList<Segment>();
        int position = 0;
        int previousEnd = -1;
        var matcher = MARKER.matcher(answer);
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            if (!markerPosition(answer, matcher.start(), previousEnd)
                    || number < 1
                    || number > citations) continue;
            if (matcher.start() > position)
                segments.add(
                        new Segment(answer.substring(position, matcher.start()), Optional.empty()));
            segments.add(new Segment(matcher.group(), Optional.of(number)));
            previousEnd = matcher.end();
            position = matcher.end();
        }
        if (position < answer.length())
            segments.add(new Segment(answer.substring(position), Optional.empty()));
        return List.copyOf(segments);
    }

    private static boolean markerPosition(String answer, int start, int previousMarkerEnd) {
        if (start == 0 || start == previousMarkerEnd) return true;
        char before = answer.charAt(start - 1);
        return !Character.isLetterOrDigit(before) && before != '_' && before != ']';
    }
}
