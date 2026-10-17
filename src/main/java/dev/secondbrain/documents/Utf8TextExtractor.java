package dev.secondbrain.documents;

import dev.secondbrain.documents.TextExtractionException.Reason;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Decodes TXT and Markdown identically, keeping Markdown syntax as reference text. */
final class Utf8TextExtractor {
    private static final int MAXIMUM_LINES = 100_000;
    private final long maximumInputBytes;

    Utf8TextExtractor(long maximumInputBytes) {
        if (maximumInputBytes < 1) {
            throw new IllegalArgumentException("Maximum extraction size must be positive");
        }
        this.maximumInputBytes = maximumInputBytes;
    }

    /** The caller owns the input stream and must close it, including after failure. */
    List<ExtractedText.Line> extract(InputStream input) {
        Objects.requireNonNull(input, "input");
        var decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        // Closing this reader would also close the caller-owned stream.
        var reader = new InputStreamReader(new BoundedInput(input, maximumInputBytes), decoder);
        var lines = new ArrayList<ExtractedText.Line>();
        var line = new StringBuilder();
        var buffer = new char[4096];
        var firstCharacter = true;
        var previousCarriageReturn = false;
        var containsText = false;
        try {
            while (true) {
                checkInterruption();
                var count = reader.read(buffer);
                if (count < 0) {
                    break;
                }
                for (var index = 0; index < count; index++) {
                    var character = buffer[index];
                    if (firstCharacter) {
                        firstCharacter = false;
                        if (character == '\uFEFF') {
                            continue;
                        }
                    }
                    if (previousCarriageReturn && character == '\n') {
                        previousCarriageReturn = false;
                        continue;
                    }
                    previousCarriageReturn = false;
                    if (character == '\r' || character == '\n') {
                        appendLine(lines, line);
                        previousCarriageReturn = character == '\r';
                    } else {
                        if (character == '\0') {
                            throw new TextExtractionException(Reason.INVALID_CONTENT);
                        }
                        line.append(character);
                        containsText = containsText || !Character.isWhitespace(character);
                    }
                }
            }
        } catch (CharacterCodingException invalidEncoding) {
            throw new TextExtractionException(Reason.INVALID_ENCODING, invalidEncoding);
        } catch (IOException unavailable) {
            throw new TextExtractionException(Reason.SOURCE_UNAVAILABLE, unavailable);
        }
        checkInterruption();
        if (!containsText) {
            throw new TextExtractionException(Reason.EMPTY_TEXT);
        }
        // Retain a final empty line when the original ends with a line separator.
        appendLine(lines, line);
        return List.copyOf(lines);
    }

    private static void appendLine(List<ExtractedText.Line> lines, StringBuilder line) {
        if (lines.size() == MAXIMUM_LINES) {
            throw new TextExtractionException(Reason.TOO_MANY_LINES);
        }
        lines.add(new ExtractedText.Line(lines.size() + 1, line.toString()));
        line.setLength(0);
    }

    private static void checkInterruption() {
        if (Thread.currentThread().isInterrupted()) {
            throw new TextExtractionException(Reason.CANCELLED);
        }
    }

    /** Read at most the byte budget plus one byte to distinguish exact-limit EOF. */
    private static final class BoundedInput extends FilterInputStream {
        private long remaining;

        private BoundedInput(InputStream input, long maximumBytes) {
            super(input);
            remaining = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            var value = in.read();
            if (value >= 0) {
                consume(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            var allowed = remaining >= length ? length : (int) remaining + 1;
            var count = in.read(bytes, offset, allowed);
            if (count > 0) {
                consume(count);
            }
            return count;
        }

        private void consume(int count) {
            if (count > remaining) {
                throw new TextExtractionException(Reason.INPUT_TOO_LARGE);
            }
            remaining -= count;
        }
    }
}
