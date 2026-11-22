package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.documents.DocumentInspectionService.Line;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

class ReadingViewTest {
    private static String text(ReadingView.Block block) {
        var text = new StringBuilder();
        for (var segment : block.segments()) text.append(segment.text());
        return text.toString();
    }

    private static List<Line> lines(String... texts) {
        var lines = new ArrayList<Line>();
        for (int i = 0; i < texts.length; i++) lines.add(new Line(i + 1, texts[i]));
        return lines;
    }

    @Test
    void joinsWrappedLinesAndSplitsParagraphsOnBlankLines() {
        var blocks =
                ReadingView.blocks(
                        lines("Lists keep their", "insertion order.", "", "Sets do not."));
        assertThat(blocks).hasSize(2);
        assertThat(text(blocks.get(0))).isEqualTo("Lists keep their insertion order.");
        assertThat(text(blocks.get(1))).isEqualTo("Sets do not.");
        // The blank line keeps an anchor in front of the next paragraph.
        assertThat(blocks.get(1).anchors()).containsExactly(3);
    }

    @Test
    void repairsHyphenatedLineBreaksButKeepsRealHyphens() {
        var blocks =
                ReadingView.blocks(
                        lines("Psychosoziale Inter-", "ventionsformen und Daten-", "Schutz."));
        assertThat(text(blocks.getFirst()))
                .isEqualTo("Psychosoziale Interventionsformen und Daten- Schutz.");
    }

    @Test
    void recognisesMarkdownHeadingsAndListItems() {
        var blocks =
                ReadingView.blocks(
                        lines("# Title", "## Part", "- first", "  wrapped", "2. second"));
        assertThat(blocks)
                .extracting(ReadingView.Block::kind)
                .containsExactly(
                        ReadingView.Kind.HEADING,
                        ReadingView.Kind.HEADING,
                        ReadingView.Kind.ITEM,
                        ReadingView.Kind.ITEM);
        assertThat(blocks.get(0).level()).isEqualTo(2);
        assertThat(blocks.get(1).level()).isEqualTo(3);
        assertThat(text(blocks.get(0))).isEqualTo("Title");
        assertThat(text(blocks.get(2))).isEqualTo("first wrapped");
    }

    @Test
    void endsAPdfParagraphOnAShortSentenceEndingLine() {
        var full = "This is a long line of extracted text that fills the page width";
        var blocks = ReadingView.blocks(lines(full, full, "ends here.", full, full));
        assertThat(blocks).hasSize(2);
        assertThat(text(blocks.get(0))).endsWith("ends here.");
    }

    @Test
    void marksPageBreaksAndKeepsEveryLineAnchor() {
        var blocks =
                ReadingView.blocks(
                        List.of(
                                new Line(1, "Page one text.", OptionalInt.of(1)),
                                new Line(2, "Page two text.", OptionalInt.of(2))));
        assertThat(blocks)
                .extracting(ReadingView.Block::kind)
                .containsExactly(
                        ReadingView.Kind.PARAGRAPH,
                        ReadingView.Kind.PAGE,
                        ReadingView.Kind.PARAGRAPH);
        assertThat(blocks.get(1).pageNumber()).isEqualTo(2);
        var anchored = new ArrayList<Integer>();
        for (var block : blocks) {
            anchored.addAll(block.anchors());
            for (var segment : block.segments()) anchored.add(segment.line());
        }
        assertThat(anchored).containsExactly(1, 2);
    }

    @Test
    void promotesTheTitleAndShortLinesThatIntroduceText() {
        var blocks =
                ReadingView.blocks(
                        lines(
                                "Psychosoziale Interventionsformen",
                                "",
                                "Daniel Hitschmann",
                                "",
                                "Einleitung",
                                "",
                                "Psychosoziale Interventionen umfassen eine Vielzahl von Methoden.",
                                "",
                                "Short closing line."));
        assertThat(blocks)
                .extracting(ReadingView.Block::kind)
                .containsExactly(
                        ReadingView.Kind.HEADING,
                        ReadingView.Kind.PARAGRAPH,
                        ReadingView.Kind.HEADING,
                        ReadingView.Kind.PARAGRAPH,
                        ReadingView.Kind.PARAGRAPH);
        assertThat(blocks.get(0).level()).isEqualTo(2);
        assertThat(blocks.get(2).level()).isEqualTo(3);
    }

    @Test
    void emptyDocumentsHaveNoBlocks() {
        assertThat(ReadingView.blocks(List.of())).isEmpty();
    }
}
