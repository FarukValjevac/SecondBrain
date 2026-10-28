package dev.secondbrain.search;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

/** Purpose-specific read projection; no document persistence or provider types cross this API. */
public record SearchResult(List<Match> matches) {
    public SearchResult {
        Objects.requireNonNull(matches, "matches");
        if (matches.size() > SearchQuery.MAXIMUM_RESULTS)
            throw new IllegalArgumentException("Too many search results");
        matches = List.copyOf(matches);
        var identities = new HashSet<UUID>();
        for (var match : matches) {
            if (!identities.add(match.passageId()))
                throw new IllegalArgumentException("Search results contain duplicate passages");
        }
    }

    @Override
    public String toString() {
        return "SearchResult[count=" + matches.size() + "]";
    }

    /** Score is cosine similarity in [-1, 1], not a probability or an answer-confidence value. */
    public record Match(
            UUID passageId,
            UUID documentId,
            long indexingVersion,
            int sequence,
            String text,
            SourceLocation location,
            double score) {
        public Match {
            Objects.requireNonNull(passageId, "passageId");
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(location, "location");
            if (indexingVersion < 1
                    || sequence < 1
                    || sequence > 20_000
                    || text.isBlank()
                    || text.length() > 2400
                    || text.codePointCount(0, text.length()) > 1200
                    || text.indexOf('\0') >= 0
                    || !Double.isFinite(score)
                    || score < -1
                    || score > 1) throw new IllegalArgumentException("Invalid retrieved passage");
        }

        @Override
        public String toString() {
            return "Match[indexingVersion="
                    + indexingVersion
                    + ", sequence="
                    + sequence
                    + ", score="
                    + score
                    + "]";
        }
    }

    /** One-based lines/pages, zero-based Unicode columns, exclusive end, as stored by documents. */
    public record SourceLocation(
            int startLine, int startColumn, int endLine, int endColumn, OptionalInt pageNumber) {
        public SourceLocation {
            Objects.requireNonNull(pageNumber, "pageNumber");
            if (startLine < 1
                    || endLine < startLine
                    || startColumn < 0
                    || endColumn < 0
                    || (startLine == endLine && endColumn <= startColumn)
                    || (pageNumber.isPresent() && pageNumber.getAsInt() < 1))
                throw new IllegalArgumentException("Invalid search source location");
        }
    }
}
