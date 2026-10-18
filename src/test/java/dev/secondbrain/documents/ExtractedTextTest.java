package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

class ExtractedTextTest {
    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final Instant EXTRACTED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void keepsAnImmutableSnapshotIncludingUnicodeAndEmptyLines() {
        var lines =
                new ArrayList<>(
                        List.of(
                                new ExtractedText.Line(1, "Café 📚"),
                                new ExtractedText.Line(2, "")));
        var extracted = new ExtractedText(DOCUMENT_ID, 1, EXTRACTED_AT, lines);

        lines.clear();

        assertThat(extracted.lines())
                .containsExactly(
                        new ExtractedText.Line(1, "Café 📚"), new ExtractedText.Line(2, ""));
        assertThatThrownBy(() -> extracted.lines().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsEmptyBlankAndNonContiguousText() {
        for (var lines :
                List.of(
                        List.<ExtractedText.Line>of(),
                        List.of(new ExtractedText.Line(1, ""), new ExtractedText.Line(2, " \t")),
                        List.of(new ExtractedText.Line(2, "Missing first line")),
                        List.of(
                                new ExtractedText.Line(1, "First"),
                                new ExtractedText.Line(3, "Gap")),
                        List.of(
                                new ExtractedText.Line(1, "First"),
                                new ExtractedText.Line(1, "Duplicate")))) {
            assertThatThrownBy(() -> new ExtractedText(DOCUMENT_ID, 1, EXTRACTED_AT, lines))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void distinguishesUnpagedTextFromPhysicalPagesIncludingBlankPages() {
        assertThat(new ExtractedText.Line(1, "Plain text").pageNumber()).isEmpty();
        var lines =
                List.of(
                        new ExtractedText.Line(1, "First page", OptionalInt.of(1)),
                        new ExtractedText.Line(2, "More first-page text", OptionalInt.of(1)),
                        new ExtractedText.Line(3, "", OptionalInt.of(2)),
                        new ExtractedText.Line(4, "Third page", OptionalInt.of(3)));

        var extracted = new ExtractedText(DOCUMENT_ID, 1, EXTRACTED_AT, lines);

        assertThat(extracted.lines()).containsExactlyElementsOf(lines);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositivePageNumbers(int pageNumber) {
        assertThatThrownBy(() -> new ExtractedText.Line(1, "Text", OptionalInt.of(pageNumber)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Page number must be positive when present");
    }

    @Test
    void requiresDocumentVersionTimestampAndLineValues() {
        var lines = List.of(new ExtractedText.Line(1, "Text"));
        assertThatThrownBy(() -> new ExtractedText(null, 1, EXTRACTED_AT, lines))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExtractedText(DOCUMENT_ID, 0, EXTRACTED_AT, lines))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExtractedText(DOCUMENT_ID, 1, null, lines))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExtractedText(DOCUMENT_ID, 1, EXTRACTED_AT, null))
                .isInstanceOf(NullPointerException.class);
        var missingLine = new ArrayList<ExtractedText.Line>();
        missingLine.add(null);
        assertThatThrownBy(() -> new ExtractedText(DOCUMENT_ID, 1, EXTRACTED_AT, missingLine))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExtractedText.Line(0, "Text"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExtractedText.Line(1, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExtractedText.Line(1, "Text", null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Private\ntext", "Private\rtext", "Private\0text"})
    void rejectsLineBreaksAndNullCharactersWithoutEchoingContent(String text) {
        assertThatThrownBy(() -> new ExtractedText.Line(1, text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("Private");
    }
}
