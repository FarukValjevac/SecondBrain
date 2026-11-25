package dev.secondbrain.documents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic local preparation; no database, provider, or mutable per-document service state.
 */
final class PassageSplitter {
    /**
     * 2: windows end at paragraph, sentence, line or word boundaries and overlaps start at a word.
     * 1 (older indexes, still searchable): fixed 1,200-code-point windows.
     */
    static final int CONFIGURATION_VERSION = 2;

    // Complete indexes of configuration 1 and 2 are both searched (SQL: IN (1, 2)).

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
        var text = content.codePoints().toArray();
        int start = 0;
        while (start < text.length) {
            checkInterruption();
            int limit = Math.min(start + MAXIMUM_CODE_POINTS, text.length);
            int end = limit < text.length ? breakBefore(text, start, limit) : limit;
            var part = new String(text, start, end - start);
            if (!part.isBlank()) {
                if (passages.size() == MAXIMUM_PASSAGES) {
                    throw new IllegalArgumentException("Document exceeds the passage count limit");
                }
                var from = position(lines, starts, start);
                var to = position(lines, starts, end);
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
            if (end == text.length) break;
            start = nextStart(text, start, end);
        }
    }

    /**
     * Where a window ending at {@code limit} should end: after the latest paragraph break, else
     * sentence end, else line break, else before the latest space, in the last 40% of the window.
     * Text without any of these (one very long word or token) is cut at the limit.
     */
    private static int breakBefore(int[] text, int start, int limit) {
        int earliest = start + MAXIMUM_CODE_POINTS * 6 / 10;
        for (int end = limit; end > earliest; end--)
            if (text[end - 1] == '\n' && text[end - 2] == '\n') return end;
        for (int end = limit; end > earliest; end--)
            if (".!?:;".indexOf(text[end - 1]) >= 0 && Character.isWhitespace(text[end]))
                return end;
        for (int end = limit; end > earliest; end--) if (text[end - 1] == '\n') return end;
        for (int end = limit; end > earliest; end--)
            if (Character.isWhitespace(text[end]) && !Character.isWhitespace(text[end - 1]))
                return end;
        return limit;
    }

    /**
     * The next window repeats about {@link #OVERLAP_CODE_POINTS} of context, starting at the
     * beginning of a word when one begins within 50 code points, and never on whitespace.
     */
    private static int nextStart(int[] text, int start, int end) {
        int next = Math.max(start + 1, end - OVERLAP_CODE_POINTS);
        for (int back = next; back > Math.max(start + 1, next - 50); back--)
            if (Character.isWhitespace(text[back - 1])) {
                next = back;
                break;
            }
        while (next < end && Character.isWhitespace(text[next])) next++;
        return next;
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
