package dev.secondbrain.notes;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** A highlighted line range with an optional note. Quotes and notes are private: never log them. */
public record Highlight(
        UUID highlightId,
        UUID documentId,
        String documentName,
        int startLine,
        int endLine,
        String quote,
        String note,
        Color color,
        Instant updatedAt) {
    public static final int MAXIMUM_NOTE = 4000;
    public static final int MAXIMUM_QUOTE = 2000;
    public static final int MAXIMUM_LINES = 501;

    public Highlight {
        Objects.requireNonNull(highlightId, "highlightId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(documentName, "documentName");
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(note, "note");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean hasNote() {
        return !note.isBlank();
    }

    @Override
    public String toString() {
        return "Highlight[id=" + highlightId + ", lines=" + startLine + "-" + endLine + "]";
    }

    /** A closed palette with readable contrast in both themes. */
    public enum Color {
        YELLOW,
        GREEN,
        BLUE,
        PINK;

        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String label() {
            return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
        }

        public static Color parse(String value) {
            for (var color : values()) if (color.token().equals(value)) return color;
            throw new IllegalArgumentException("Unknown highlight colour");
        }
    }
}
