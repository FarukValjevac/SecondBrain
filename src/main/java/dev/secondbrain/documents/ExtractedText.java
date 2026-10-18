package dev.secondbrain.documents;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

/** Private text belonging to one document processing version. Do not log this record. */
record ExtractedText(
        UUID documentId, long processingVersion, Instant extractedAt, List<Line> lines) {
    ExtractedText {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(extractedAt, "extractedAt");
        if (processingVersion < 1) {
            throw new IllegalArgumentException("Processing version must be positive");
        }
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        var containsText = false;
        for (var index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            if (line.number() != index + 1) {
                throw new IllegalArgumentException(
                        "Text lines must be contiguous and start at one");
            }
            containsText = containsText || !line.text().isBlank();
        }
        if (!containsText) {
            throw new IllegalArgumentException(
                    "Extracted text must contain non-whitespace content");
        }
    }

    /** Page numbers are physical, one-based PDF pages; unpaged text has no page number. */
    record Line(int number, String text, OptionalInt pageNumber) {
        Line(int number, String text) {
            this(number, text, OptionalInt.empty());
        }

        Line {
            if (number < 1) {
                throw new IllegalArgumentException("Line number must be positive");
            }
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(pageNumber, "pageNumber");
            if (pageNumber.isPresent() && pageNumber.getAsInt() < 1) {
                throw new IllegalArgumentException("Page number must be positive when present");
            }
            if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "Line text cannot contain line breaks or null characters");
            }
        }
    }
}
