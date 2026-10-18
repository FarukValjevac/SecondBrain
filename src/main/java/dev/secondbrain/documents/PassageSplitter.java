package dev.secondbrain.documents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic local preparation; no database, provider, or mutable per-document service state.
 */
final class PassageSplitter {
    static final int CONFIGURATION_VERSION = 1;
    static final int MAXIMUM_CODE_POINTS = 1200;
    static final int OVERLAP_CODE_POINTS = 200;
    static final int MAXIMUM_PASSAGES = 20_000;

    PreparedPassages split(IndexingVersion version, ExtractedText extracted)
            throws InterruptedException {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(extracted, "extracted");
        if (!version.documentId().equals(extracted.documentId())
                || version.extractionVersion() != extracted.processingVersion()) {
            throw new IllegalArgumentException("Index generation must match the extracted source");
        }
        var passages = new ArrayList<PreparedPassages.Passage>();
        var lines = extracted.lines();
        int first = 0;
        while (first < lines.size()) {
            checkInterruption();
            int end = first + 1;
            var page = lines.get(first).pageNumber();
            while (end < lines.size() && page.equals(lines.get(end).pageNumber())) end++;
            // Page changes are hard boundaries: context overlap cannot blur a PDF citation.
            splitBlock(lines.subList(first, end), passages);
            first = end;
        }
        return new PreparedPassages(version, CONFIGURATION_VERSION, passages);
    }

    private static void splitBlock(
            List<ExtractedText.Line> lines, List<PreparedPassages.Passage> passages)
            throws InterruptedException {
        var content = new StringBuilder();
        var starts = new int[lines.size()];
        int points = 0;
        for (int index = 0; index < lines.size(); index++) {
            checkInterruption();
            if (index > 0) {
                content.append('\n');
                points++;
            }
            starts[index] = points;
            var line = lines.get(index).text();
            content.append(line);
            points += line.codePointCount(0, line.length());
        }
        var text = content.toString();
        int remaining = points;
        int startPoint = 0;
        int start = 0;
        while (remaining > 0) {
            checkInterruption();
            int length = Math.min(MAXIMUM_CODE_POINTS, remaining);
            int end = text.offsetByCodePoints(start, length);
            var part = text.substring(start, end);
            if (!part.isBlank()) {
                if (passages.size() == MAXIMUM_PASSAGES) {
                    throw new IllegalArgumentException("Document exceeds the passage count limit");
                }
                var from = position(lines, starts, startPoint);
                var to = position(lines, starts, startPoint + length);
                passages.add(
                        new PreparedPassages.Passage(
                                passages.size() + 1,
                                part,
                                new PreparedPassages.Location(
                                        from.line(),
                                        from.column(),
                                        to.line(),
                                        to.column(),
                                        lines.getFirst().pageNumber())));
            }
            if (end == text.length()) break;
            // Count once per block, then advance linearly; avoid recounting the remaining suffix.
            start = text.offsetByCodePoints(end, -OVERLAP_CODE_POINTS);
            startPoint += length - OVERLAP_CODE_POINTS;
            remaining = points - startPoint;
        }
    }

    private static Position position(List<ExtractedText.Line> lines, int[] starts, int offset) {
        int index = Arrays.binarySearch(starts, offset);
        if (index < 0) index = -index - 2;
        return new Position(lines.get(index).number(), offset - starts[index]);
    }

    private static void checkInterruption() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Passage preparation interrupted");
        }
    }

    private record Position(int line, int column) {}
}
