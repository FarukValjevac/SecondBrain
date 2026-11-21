package dev.secondbrain.documents;

import dev.secondbrain.documents.TextExtractionException.Reason;

import java.util.ArrayList;
import java.util.List;

/** Splits text already produced by an extractor into the shared numbered-line form. */
final class TextLines {
    static final int MAXIMUM_LINES = 100_000;

    private TextLines() {}

    /** Accepts \r\n, \r and \n; consecutive blank lines collapse to one. */
    static List<ExtractedText.Line> split(CharSequence text) {
        var lines = new ArrayList<ExtractedText.Line>();
        var line = new StringBuilder();
        var containsText = false;
        var previousBlank = true;
        for (var index = 0; index <= text.length(); index++) {
            var end = index == text.length();
            var character = end ? '\n' : text.charAt(index);
            if (!end && character == '\r') {
                if (index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
                character = '\n';
            }
            if (character != '\n') {
                if (character == '\0') throw new TextExtractionException(Reason.INVALID_CONTENT);
                line.append(character);
                continue;
            }
            var content = stripTrailing(line);
            line.setLength(0);
            var blank = content.isBlank();
            // Markup removal leaves runs of empty lines; keep paragraph breaks, not the gaps.
            if (blank && (previousBlank || end)) continue;
            if (lines.size() == MAXIMUM_LINES)
                throw new TextExtractionException(Reason.TOO_MANY_LINES);
            lines.add(new ExtractedText.Line(lines.size() + 1, content));
            containsText = containsText || !blank;
            previousBlank = blank;
        }
        if (!containsText) throw new TextExtractionException(Reason.EMPTY_TEXT);
        if (lines.getLast().text().isBlank()) lines.removeLast();
        return List.copyOf(lines);
    }

    private static String stripTrailing(StringBuilder line) {
        var end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        return line.substring(0, end);
    }

    static void checkInterruption() {
        if (Thread.currentThread().isInterrupted())
            throw new TextExtractionException(Reason.CANCELLED);
    }
}
