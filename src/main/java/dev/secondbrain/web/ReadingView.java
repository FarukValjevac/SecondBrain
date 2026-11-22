package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentInspectionService.Line;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Rebuilds readable blocks from extracted lines: wrapped lines become paragraphs, hyphenated line
 * breaks are repaired, Markdown headings and list items are recognised, and PDF page breaks are
 * marked. Every source line keeps an anchor ({@code line-N}) so source links still land on it.
 * Presentation only: the stored text and passages are unchanged.
 */
final class ReadingView {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern LIST_ITEM =
            Pattern.compile("^\\s*(?:[-*+•▪◦–]|\\d{1,3}[.)])\\s+(.+)$");
    private static final Pattern SENTENCE_END = Pattern.compile(".*[.!?:;\"”»)]$");

    private ReadingView() {}

    enum Kind {
        PAGE,
        HEADING,
        PARAGRAPH,
        ITEM
    }

    /** One displayed piece of a source line; {@code text} already ends with its separator. */
    record Segment(int line, String text) {
        Segment {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * A block of the reading view. {@code anchors} are blank source lines just before it, kept so a
     * link to them still scrolls here. {@code level} is the heading level (2–4) or 0.
     */
    record Block(
            Kind kind, int level, List<Segment> segments, List<Integer> anchors, int pageNumber) {
        Block {
            Objects.requireNonNull(kind, "kind");
            segments = List.copyOf(segments);
            anchors = List.copyOf(anchors);
        }

        public boolean isPage() {
            return kind == Kind.PAGE;
        }

        public boolean isHeading() {
            return kind == Kind.HEADING;
        }

        public boolean isItem() {
            return kind == Kind.ITEM;
        }

        public boolean isParagraph() {
            return kind == Kind.PARAGRAPH;
        }
    }

    static List<Block> blocks(List<Line> lines) {
        var blocks = new ArrayList<Block>();
        var builder = new Builder(blocks, typicalLength(lines));
        OptionalInt page = OptionalInt.empty();
        for (var line : lines) {
            if (line.pageNumber().isPresent()
                    && page.isPresent()
                    && line.pageNumber().getAsInt() != page.getAsInt()) {
                builder.flush();
                blocks.add(
                        new Block(
                                Kind.PAGE,
                                0,
                                List.of(),
                                builder.takeAnchors(),
                                line.pageNumber().getAsInt()));
            }
            if (line.pageNumber().isPresent()) page = line.pageNumber();
            builder.add(line);
        }
        builder.flush();
        if (!builder.anchors.isEmpty())
            blocks.add(new Block(Kind.PARAGRAPH, 0, List.of(), builder.takeAnchors(), 0));
        return promoteHeadings(blocks);
    }

    /**
     * Extracted text has no heading markup, so short standalone lines are promoted: the first block
     * becomes the title, and later ones become headings when real text follows them.
     */
    private static List<Block> promoteHeadings(List<Block> blocks) {
        var result = new ArrayList<Block>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.get(i);
            if (!headingLike(block)) {
                result.add(block);
                continue;
            }
            Block next = null;
            for (int j = i + 1; j < blocks.size() && next == null; j++)
                if (!blocks.get(j).isPage()) next = blocks.get(j);
            boolean first = result.stream().noneMatch(previous -> !previous.isPage());
            boolean introducesText = next != null && next.isParagraph() && !headingLike(next);
            if (first || introducesText)
                result.add(
                        new Block(
                                Kind.HEADING, first ? 2 : 3, block.segments(), block.anchors(), 0));
            else result.add(block);
        }
        return List.copyOf(result);
    }

    private static boolean headingLike(Block block) {
        if (!block.isParagraph() || block.segments().size() != 1) return false;
        var text = block.segments().getFirst().text().strip();
        return !text.isEmpty()
                && text.length() <= 70
                && Character.isLetterOrDigit(text.codePointAt(0))
                && !Character.isLowerCase(text.codePointAt(0))
                && ".,;:!?".indexOf(text.charAt(text.length() - 1)) < 0;
    }

    /** The length of a "full" line, used to spot the short last line of a PDF paragraph. */
    private static int typicalLength(List<Line> lines) {
        var lengths =
                lines.stream()
                        .mapToInt(line -> line.text().strip().length())
                        .filter(n -> n > 0)
                        .toArray();
        if (lengths.length == 0) return 0;
        Arrays.sort(lengths);
        return lengths[(int) Math.floor(lengths.length * 0.75)];
    }

    private static final class Builder {
        private final List<Block> blocks;
        private final int typical;
        private final List<Integer> anchors = new ArrayList<>();
        private List<Segment> paragraph = new ArrayList<>();
        private List<Integer> paragraphAnchors = List.of();
        private String previous = "";

        Builder(List<Block> blocks, int typical) {
            this.blocks = blocks;
            this.typical = typical;
        }

        void add(Line line) {
            var text = line.text().strip();
            if (text.isEmpty()) {
                flush();
                anchors.add(line.number());
                return;
            }
            var heading = HEADING.matcher(text);
            if (heading.matches()) {
                flush();
                int level = Math.min(4, heading.group(1).length() + 1);
                blocks.add(
                        new Block(
                                Kind.HEADING,
                                level,
                                List.of(new Segment(line.number(), heading.group(2).strip())),
                                takeAnchors(),
                                0));
                return;
            }
            var item = LIST_ITEM.matcher(text);
            if (item.matches()) {
                flush();
                start();
                paragraph.add(new Segment(line.number(), item.group(1).strip()));
                previous = item.group(1).strip();
                // A list item stays one block; wrapped continuation lines join it.
                kind = Kind.ITEM;
                return;
            }
            if (!paragraph.isEmpty() && endsParagraph(previous)) flush();
            if (paragraph.isEmpty()) start();
            append(line.number(), text);
        }

        private Kind kind = Kind.PARAGRAPH;

        /** A short line that ends a sentence usually closes a paragraph in extracted PDF text. */
        private boolean endsParagraph(String line) {
            return typical > 0
                    && line.length() < typical * 0.6
                    && SENTENCE_END.matcher(line).matches();
        }

        private void start() {
            paragraphAnchors = takeAnchors();
            kind = Kind.PARAGRAPH;
        }

        private void append(int number, String text) {
            if (!paragraph.isEmpty()) {
                var last = paragraph.removeLast();
                var lastText = last.text();
                // "Inter-\nvention" was one word split by the line break.
                if (lastText.length() > 1
                        && lastText.endsWith("-")
                        && Character.isLetter(lastText.charAt(lastText.length() - 2))
                        && Character.isLowerCase(text.codePointAt(0)))
                    paragraph.add(
                            new Segment(last.line(), lastText.substring(0, lastText.length() - 1)));
                else paragraph.add(new Segment(last.line(), lastText + " "));
            }
            paragraph.add(new Segment(number, text));
            previous = text;
        }

        void flush() {
            if (paragraph.isEmpty()) return;
            blocks.add(new Block(kind, 0, paragraph, paragraphAnchors, 0));
            paragraph = new ArrayList<>();
            paragraphAnchors = List.of();
            previous = "";
            kind = Kind.PARAGRAPH;
        }

        List<Integer> takeAnchors() {
            var taken = List.copyOf(anchors);
            anchors.clear();
            return taken;
        }
    }
}
