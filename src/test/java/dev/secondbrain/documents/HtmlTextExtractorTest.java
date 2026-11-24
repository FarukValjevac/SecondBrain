package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.secondbrain.documents.TextExtractionException.Reason;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

class HtmlTextExtractorTest {
    private final HtmlTextExtractor extractor =
            new HtmlTextExtractor(new Utf8TextExtractor(1024 * 1024));

    @Test
    void keepsVisibleTextAndTurnsBlocksIntoLines() {
        var html =
                """
                <!DOCTYPE html><html><head><title>Java notes</title>
                <style>p { color: red; }</style><script>alert("hidden")</script></head>
                <body><!-- private comment --><h1>ArrayList</h1>
                <p>Keeps <b>order</b> &amp; permits duplicates.</p>
                <ul><li>First</li><li>Second&nbsp;item &#x1F4DA;</li></ul>
                <table><tr><td>Key</td><td>Value</td></tr></table></body></html>
                """;
        assertThat(lines(html))
                // One blank line separates blocks, like paragraphs in a Word document.
                .containsExactly(
                        "Java notes",
                        "",
                        "ArrayList",
                        "",
                        "Keeps order & permits duplicates.",
                        "",
                        "First",
                        "",
                        "Second item 📚",
                        "",
                        " Key  Value");
    }

    @Test
    void decodesNamedEntitiesThatEuropeanPagesUse() {
        assertThat(
                        lines(
                                "<p>Gr&uuml;&szlig;e aus K&ouml;ln &ndash; caf&eacute; &mdash;"
                                        + " &bdquo;Zitat&ldquo; &hellip; 5&nbsp;&euro; &AElig;</p>"))
                .containsExactly("Grüße aus Köln – café — „Zitat“ … 5 € Æ");
        // Unknown names stay literal, so no text is ever lost.
        assertThat(lines("<p>&notanentity; &amp;</p>")).containsExactly("&notanentity; &");
    }

    @Test
    void treatsMarkupAsTextNeverAsInstructions() {
        assertThat(lines("<p>a &lt;script&gt; tag</p><p>1 < 2 and 3 > 2</p>"))
                .containsExactly("a <script> tag", "", "1 < 2 and 3 > 2");
    }

    @Test
    void unclosedHiddenElementsAndCommentsDropTheRestRatherThanLeakingIt() {
        assertThat(lines("<p>Visible</p><script>secret")).containsExactly("Visible");
        assertThat(lines("<p>Visible</p><!-- secret")).containsExactly("Visible");
    }

    @Test
    void manyUnclosedAngleBracketsStayLinear() {
        var html = "<".repeat(200_000);
        var started = System.nanoTime();
        assertThat(HtmlTextExtractor.visibleText(html)).hasSize(200_000);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void usesTheTextUploadRulesForEncodingAndEmptyPages() {
        assertThatThrownBy(
                        () ->
                                extractor.extract(
                                        new ByteArrayInputStream(new byte[] {(byte) 0xC3, 0x28})))
                .isInstanceOfSatisfying(
                        TextExtractionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.INVALID_ENCODING));
        assertThatThrownBy(() -> lines("<html><script>only code</script></html>"))
                .isInstanceOfSatisfying(
                        TextExtractionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.EMPTY_TEXT));
    }

    private java.util.List<String> lines(String html) {
        return extractor
                .extract(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)))
                .stream()
                .map(ExtractedText.Line::text)
                .toList();
    }
}
