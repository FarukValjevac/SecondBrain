package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.secondbrain.documents.TextExtractionException.Reason;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

class Utf8TextExtractorTest {
    @Test
    void preservesUnicodeMarkdownWhitespaceAndNumberedSourceLines() throws IOException {
        var source = "\uFEFF# Café 📚\r\n\r\n  **Study**\r<script>alert('note')</script>\n";
        try (var input = input(source)) {
            var lines = new Utf8TextExtractor(1024).extract(input);

            assertThat(lines)
                    .containsExactly(
                            new ExtractedText.Line(1, "# Café 📚"),
                            new ExtractedText.Line(2, ""),
                            new ExtractedText.Line(3, "  **Study**"),
                            new ExtractedText.Line(4, "<script>alert('note')</script>"),
                            new ExtractedText.Line(5, ""));
            assertThatThrownBy(lines::clear).isInstanceOf(UnsupportedOperationException.class);
            // The decoder's result satisfies the existing persistence value's invariants.
            assertThat(new ExtractedText(UUID.randomUUID(), 1, Instant.EPOCH, lines).lines())
                    .containsExactlyElementsOf(lines);
        }
    }

    @Test
    void handlesSplitUtf8SequencesAndCrLfAcrossReadBuffers() throws IOException {
        var source = "x".repeat(4095) + "\r\nCafé 📚\n\uFEFFinside";
        try (var input =
                new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)) {
                    @Override
                    public synchronized int read(byte[] bytes, int offset, int length) {
                        return super.read(bytes, offset, Math.min(length, 1));
                    }
                }) {
            assertThat(new Utf8TextExtractor(10_000).extract(input))
                    .containsExactly(
                            new ExtractedText.Line(1, "x".repeat(4095)),
                            new ExtractedText.Line(2, "Café 📚"),
                            new ExtractedText.Line(3, "\uFEFFinside"));
        }
    }

    @ParameterizedTest
    @MethodSource("invalidEncodings")
    void rejectsMalformedOrTruncatedEncodingWithoutReplacingCharacters(byte[] bytes)
            throws IOException {
        try (var input = new ByteArrayInputStream(bytes)) {
            assertThatThrownBy(() -> new Utf8TextExtractor(1024).extract(input))
                    .isInstanceOfSatisfying(
                            TextExtractionException.class,
                            failure ->
                                    assertThat(failure.reason()).isEqualTo(Reason.INVALID_ENCODING))
                    .hasMessage("The document must use valid UTF-8 encoding.");
        }
    }

    private static Stream<byte[]> invalidEncodings() {
        return Stream.of(
                new byte[] {'c', 'a', 'f', (byte) 0xE9},
                new byte[] {(byte) 0xC3, 0x28},
                new byte[] {(byte) 0xE2, (byte) 0x82},
                new byte[] {(byte) 0xFF, (byte) 0xFE, 'a', 0},
                new byte[] {(byte) 0xC0, (byte) 0x80},
                new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80});
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t\r\n\u2003", "\uFEFF", "\uFEFF \n"})
    void rejectsEmptyOrWhitespaceOnlyContent(String source) throws IOException {
        try (var input = input(source)) {
            assertThatThrownBy(() -> new Utf8TextExtractor(1024).extract(input))
                    .isInstanceOfSatisfying(
                            TextExtractionException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(Reason.EMPTY_TEXT));
        }
    }

    @Test
    void rejectsNullCharactersWithoutIncludingPrivateTextInTheMessage() throws IOException {
        try (var input = input("private note\0")) {
            assertThatThrownBy(() -> new Utf8TextExtractor(1024).extract(input))
                    .isInstanceOfSatisfying(
                            TextExtractionException.class,
                            failure ->
                                    assertThat(failure.reason()).isEqualTo(Reason.INVALID_CONTENT))
                    .hasMessageNotContaining("private note");
        }
    }

    @Test
    void countsTheInputLimitInBytesAndAcceptsExactLimitEof() throws IOException {
        try (var input = input("Café")) {
            assertThat(new Utf8TextExtractor(5).extract(input))
                    .containsExactly(new ExtractedText.Line(1, "Café"));
        }
        try (var input = input("Café")) {
            assertThatThrownBy(() -> new Utf8TextExtractor(4).extract(input))
                    .isInstanceOfSatisfying(
                            TextExtractionException.class,
                            failure ->
                                    assertThat(failure.reason()).isEqualTo(Reason.INPUT_TOO_LARGE));
        }
    }

    @Test
    void stopsReadingAnOversizedSourceAfterOneOverflowByte() throws IOException {
        try (var input =
                new InputStream() {
                    private int consumed;

                    @Override
                    public int read() {
                        consumed++;
                        return 'x';
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) {
                        Arrays.fill(bytes, offset, offset + length, (byte) 'x');
                        consumed += length;
                        return length;
                    }
                }) {
            assertThatThrownBy(() -> new Utf8TextExtractor(5).extract(input))
                    .isInstanceOfSatisfying(
                            TextExtractionException.class,
                            failure ->
                                    assertThat(failure.reason()).isEqualTo(Reason.INPUT_TOO_LARGE));
            assertThat(input.consumed).isEqualTo(6);
        }
    }

    @Test
    void boundsLineAllocationAndCountsTheTrailingEmptyLine() throws IOException {
        var source = "x\n".repeat(99_999) + "last";
        try (var input = input(source)) {
            var lines = new Utf8TextExtractor(300_000).extract(input);
            assertThat(lines).hasSize(100_000);
            assertThat(lines.getLast()).isEqualTo(new ExtractedText.Line(100_000, "last"));
        }
        try (var input = input(source + "\n")) {
            assertThatThrownBy(() -> new Utf8TextExtractor(300_000).extract(input))
                    .isInstanceOfSatisfying(
                            TextExtractionException.class,
                            failure ->
                                    assertThat(failure.reason()).isEqualTo(Reason.TOO_MANY_LINES));
        }
    }

    @Test
    void preservesReadFailureCauseButExposesOnlyAFixedMessage() throws IOException {
        var unavailable = new IOException("private/path/notes.txt");
        try (var input =
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw unavailable;
                    }
                }) {
            assertThatThrownBy(() -> new Utf8TextExtractor(1024).extract(input))
                    .isInstanceOfSatisfying(
                            TextExtractionException.class,
                            failure ->
                                    assertThat(failure.reason())
                                            .isEqualTo(Reason.SOURCE_UNAVAILABLE))
                    .hasCause(unavailable)
                    .hasMessage("The original document could not be read.");
        }
    }

    @Test
    void leavesTheCallerOwnedStreamOpenOnSuccessAndFailure() throws IOException {
        for (var source : new String[] {"Note", "\0"}) {
            try (var input =
                    new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)) {
                        private boolean closed;

                        @Override
                        public void close() throws IOException {
                            closed = true;
                            super.close();
                        }
                    }) {
                var extractor = new Utf8TextExtractor(1024);
                if (source.equals("Note")) {
                    extractor.extract(input);
                } else {
                    assertThatThrownBy(() -> extractor.extract(input))
                            .isInstanceOf(TextExtractionException.class);
                }
                assertThat(input.closed).isFalse();
            }
        }
    }

    @Test
    void honorsCancellationWithoutClearingTheThreadInterruptFlag() throws IOException {
        try (var input = input("Note")) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> new Utf8TextExtractor(1024).extract(input))
                        .isInstanceOfSatisfying(
                                TextExtractionException.class,
                                failure ->
                                        assertThat(failure.reason()).isEqualTo(Reason.CANCELLED));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(input.available()).isEqualTo(4);
            } finally {
                Thread.interrupted();
            }
        }
    }

    private static ByteArrayInputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
