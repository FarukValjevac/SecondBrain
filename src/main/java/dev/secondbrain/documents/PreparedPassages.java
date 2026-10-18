package dev.secondbrain.documents;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Private preparation output for one index generation; never log its text-bearing records. */
record PreparedPassages(IndexingVersion version, int configurationVersion, List<Passage> passages) {
    PreparedPassages {
        Objects.requireNonNull(version, "version");
        if (configurationVersion < 1) {
            throw new IllegalArgumentException("Passage configuration version must be positive");
        }
        passages = List.copyOf(Objects.requireNonNull(passages, "passages"));
        if (passages.isEmpty()) throw new IllegalArgumentException("Passages must contain text");
        for (int index = 0; index < passages.size(); index++) {
            if (passages.get(index).sequence() != index + 1) {
                throw new IllegalArgumentException(
                        "Passage sequence must be contiguous and start at one");
            }
        }
    }

    record Passage(int sequence, String text, Location location) {
        Passage {
            if (sequence < 1)
                throw new IllegalArgumentException("Passage sequence must be positive");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(location, "location");
            if (text.isBlank()) throw new IllegalArgumentException("Passage must contain text");
        }
    }

    /** Lines are one-based; columns count Unicode code points from zero; the end is exclusive. */
    record Location(
            int startLine, int startColumn, int endLine, int endColumn, OptionalInt pageNumber) {
        Location {
            Objects.requireNonNull(pageNumber, "pageNumber");
            if (startLine < 1
                    || endLine < startLine
                    || startColumn < 0
                    || endColumn < 0
                    || (startLine == endLine && endColumn <= startColumn)
                    || (pageNumber.isPresent() && pageNumber.getAsInt() < 1)) {
                throw new IllegalArgumentException("Invalid passage source location");
            }
        }
    }
}
