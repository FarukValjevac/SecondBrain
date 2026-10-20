package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.IntStream;

class PassageSplitterTest {
    private static final UUID DOCUMENT = UUID.randomUUID();
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final IndexingVersion VERSION = new IndexingVersion(DOCUMENT, 7, 2, CREATED);
    private final PassageSplitter splitter = new PassageSplitter();

    @ParameterizedTest
    @ValueSource(
            strings = {"Study notes\nSecond line\n", "# Revision\n\n- Café 📚\nLiteral <markup>"})
    void preservesShortTextAndMarkdownWithExactUnpagedSourceLocations(String source)
            throws Exception {
        var extracted = text(source);
        var result = splitter.split(VERSION, extracted);
        var last = extracted.lines().getLast();
        assertThat(result.version()).isEqualTo(VERSION);
        assertThat(result.configurationVersion()).isEqualTo(1);
        assertThat(result.passages())
                .containsExactly(
                        new PreparedPassages.Passage(
                                1,
                                source,
                                new PreparedPassages.Location(
                                        1,
                                        0,
                                        last.number(),
                                        last.text().codePointCount(0, last.text().length()),
                                        OptionalInt.empty())));
        assertThat(splitter.split(VERSION, extracted)).isEqualTo(result);
        assertThatThrownBy(() -> result.passages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @CsvSource({"1199, 1", "1200, 1", "1201, 2", "2200, 2", "2201, 3"})
    void boundsSizeAndUsesExactlyTwoHundredCodePointsOfOverlap(int size, int count)
            throws Exception {
        var source = "x".repeat(size - 1) + "!";
        var passages = splitter.split(VERSION, text(source)).passages();
        assertThat(passages).hasSize(count);
        for (int index = 0; index < count; index++) {
            var passage = passages.get(index);
            int from = index * 1000;
            int to = Math.min(from + 1200, size);
            assertThat(passage.sequence()).isEqualTo(index + 1);
            assertThat(passage.text()).isEqualTo(source.substring(from, to));
            assertThat(passage.location())
                    .isEqualTo(new PreparedPassages.Location(1, from, 1, to, OptionalInt.empty()));
            assertThat(passage.text().codePointCount(0, passage.text().length()))
                    .isLessThanOrEqualTo(1200);
            if (index > 0)
                assertThat(passages.get(index - 1).text().substring(1000))
                        .isEqualTo(passage.text().substring(0, 200));
        }
    }

    @Test
    void preservesBlankLinesAndMapsAnExclusiveEndImmediatelyAfterANewline() throws Exception {
        var passages = splitter.split(VERSION, text("x".repeat(1199) + "\n\nEnd")).passages();
        assertThat(passages).hasSize(2);
        assertThat(passages.getFirst().text()).isEqualTo("x".repeat(1199) + "\n");
        assertThat(passages.getFirst().location())
                .isEqualTo(new PreparedPassages.Location(1, 0, 2, 0, OptionalInt.empty()));
        assertThat(passages.getLast().text()).isEqualTo("x".repeat(199) + "\n\nEnd");
        assertThat(passages.getLast().location())
                .isEqualTo(new PreparedPassages.Location(1, 1000, 3, 3, OptionalInt.empty()));
    }

    @Test
    void unicodeBoundariesAndColumnsDoNotSplitSupplementaryCharacters() throws Exception {
        var source = "Café 📚".repeat(200) + "終";
        var passages = splitter.split(VERSION, text(source)).passages();
        assertThat(passages).hasSize(2);
        assertThat(passages.getFirst().text()).isEqualTo("Café 📚".repeat(200));
        assertThat(passages.getFirst().location().endColumn()).isEqualTo(1200);
        assertThat(passages.getLast().location().startColumn()).isEqualTo(1000);
        assertThat(passages.getLast().location().endColumn()).isEqualTo(1201);
        for (var passage : passages) {
            var from = source.offsetByCodePoints(0, passage.location().startColumn());
            var to = source.offsetByCodePoints(0, passage.location().endColumn());
            assertThat(passage.text()).isEqualTo(source.substring(from, to));
            assertThat(passage.text().codePoints())
                    .noneMatch(point -> point >= 0xD800 && point <= 0xDFFF);
        }
    }

    @Test
    void keepsPhysicalPdfPagesAndOriginalLineNumbersWhileOmittingBlankPages() throws Exception {
        var extracted =
                new ExtractedText(
                        DOCUMENT,
                        2,
                        CREATED,
                        List.of(
                                new ExtractedText.Line(1, "Hello", OptionalInt.of(1)),
                                new ExtractedText.Line(2, "again", OptionalInt.of(1)),
                                new ExtractedText.Line(3, "", OptionalInt.of(2)),
                                new ExtractedText.Line(4, "World", OptionalInt.of(3))));
        var passages = splitter.split(VERSION, extracted).passages();
        assertThat(passages)
                .containsExactly(
                        new PreparedPassages.Passage(
                                1,
                                "Hello\nagain",
                                new PreparedPassages.Location(1, 0, 2, 5, OptionalInt.of(1))),
                        new PreparedPassages.Passage(
                                2,
                                "World",
                                new PreparedPassages.Location(4, 0, 4, 5, OptionalInt.of(3))));
    }

    @Test
    void resetsOverlapAtEachPageAndSplitsOversizedPdfLines() throws Exception {
        var extracted =
                new ExtractedText(
                        DOCUMENT,
                        2,
                        CREATED,
                        List.of(
                                new ExtractedText.Line(1, "a".repeat(1201), OptionalInt.of(1)),
                                new ExtractedText.Line(2, "b".repeat(1201), OptionalInt.of(2))));
        var passages = splitter.split(VERSION, extracted).passages();
        assertThat(passages).hasSize(4);
        assertThat(passages)
                .extracting(p -> p.location().startColumn())
                .containsExactly(0, 1000, 0, 1000);
        assertThat(passages)
                .extracting(p -> p.location().pageNumber().orElseThrow())
                .containsExactly(1, 1, 2, 2);
        assertThat(passages.get(1).text()).isEqualTo("a".repeat(201));
        assertThat(passages.get(2).text()).isEqualTo("b".repeat(1200));
    }

    @Test
    void skipsWhitespaceOnlyWindowsWithoutTrimmingTextOrLeavingSequenceGaps() throws Exception {
        var result = splitter.split(VERSION, text(" ".repeat(1200) + "Note"));
        assertThat(result.passages())
                .containsExactly(
                        new PreparedPassages.Passage(
                                1,
                                " ".repeat(200) + "Note",
                                new PreparedPassages.Location(
                                        1, 1000, 1, 1204, OptionalInt.empty())));
    }

    @Test
    void rejectsTheWrongDocumentOrExtractionGenerationWithoutEchoingText() {
        var extracted = text("private-study-sentinel");
        for (var version :
                List.of(
                        new IndexingVersion(UUID.randomUUID(), 7, 2, CREATED),
                        new IndexingVersion(DOCUMENT, 7, 3, CREATED))) {
            assertThatThrownBy(() -> splitter.split(version, extracted))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("private-study-sentinel");
        }
    }

    @Test
    void boundsTotalOutputEvenWhenEverySourceBlockContainsText() {
        var lines =
                IntStream.rangeClosed(1, PassageSplitter.MAXIMUM_PASSAGES + 1)
                        .mapToObj(
                                number ->
                                        new ExtractedText.Line(
                                                number, "Text", OptionalInt.of(number)))
                        .toList();
        var extracted = new ExtractedText(DOCUMENT, 2, CREATED, lines);
        assertThatThrownBy(() -> splitter.split(VERSION, extracted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Document exceeds the passage count limit");
    }

    @Test
    void honorsCancellationAndPreservesTheInterruptionFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> splitter.split(VERSION, text("Study notes")))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void preparationOutputCopiesCollectionsAndRejectsInvalidSourceRangesOrSequence() {
        var location = new PreparedPassages.Location(1, 0, 1, 4, OptionalInt.empty());
        var parts = new ArrayList<>(List.of(new PreparedPassages.Passage(1, "Note", location)));
        var result = new PreparedPassages(VERSION, 1, parts);
        parts.clear();
        assertThat(result.passages()).hasSize(1);
        assertThatThrownBy(() -> new PreparedPassages(VERSION, 0, result.passages()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PreparedPassages(
                                        VERSION,
                                        1,
                                        List.of(new PreparedPassages.Passage(2, "Note", location))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreparedPassages.Passage(1, " \n", location))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreparedPassages.Location(1, 4, 1, 4, OptionalInt.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreparedPassages.Location(2, 0, 1, 4, OptionalInt.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreparedPassages.Location(1, 0, 1, 4, OptionalInt.of(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ExtractedText text(String content) {
        var values = content.split("\n", -1);
        var lines =
                IntStream.range(0, values.length)
                        .mapToObj(index -> new ExtractedText.Line(index + 1, values[index]))
                        .toList();
        return new ExtractedText(DOCUMENT, 2, CREATED, lines);
    }
}
