package dev.secondbrain.documents;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Reads the visible text of a UTF-8 HTML page. A single forward scan keeps the work linear in the
 * input size; scripts, styles and comments are dropped and block elements become line breaks.
 */
final class HtmlTextExtractor {
    private static final Set<String> HIDDEN = Set.of("script", "style", "template", "noscript");
    private static final Set<String> BLOCKS =
            Set.of(
                    "address",
                    "article",
                    "aside",
                    "blockquote",
                    "br",
                    "dd",
                    "div",
                    "dl",
                    "dt",
                    "figcaption",
                    "figure",
                    "footer",
                    "form",
                    "h1",
                    "h2",
                    "h3",
                    "h4",
                    "h5",
                    "h6",
                    "header",
                    "hr",
                    "li",
                    "main",
                    "nav",
                    "ol",
                    "p",
                    "pre",
                    "section",
                    "table",
                    "title",
                    "tr",
                    "ul");
    private static final Set<String> CELLS = Set.of("td", "th");
    private final Utf8TextExtractor decoder;

    HtmlTextExtractor(Utf8TextExtractor decoder) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /** The caller owns the input stream. Encoding, size and null checks match TXT uploads. */
    List<ExtractedText.Line> extract(InputStream input) {
        var source = new StringBuilder();
        for (var line : decoder.extract(input)) source.append(line.text()).append('\n');
        return TextLines.split(visibleText(source));
    }

    static String visibleText(CharSequence html) {
        var text = new StringBuilder(html.length());
        var length = html.length();
        var index = 0;
        while (index < length) {
            if ((index & 0xFFF) == 0) TextLines.checkInterruption();
            var character = html.charAt(index);
            if (character == '&') {
                index = entity(html, index, text);
                continue;
            }
            if (character != '<') {
                text.append(character);
                index++;
                continue;
            }
            if (startsWith(html, index, "<!--")) {
                index = after(html, index + 4, "-->");
                continue;
            }
            var close = indexOf(html, index + 1, '>');
            if (close < 0) {
                // No tag ever closes; the rest is plain text. Avoids rescanning per '<'.
                text.append(html, index, length);
                break;
            }
            var name = tagName(html, index + 1, close);
            if (name.isEmpty()) {
                text.append(character);
                index++;
                continue;
            }
            var closing = html.charAt(index + 1) == '/';
            if (!closing && HIDDEN.contains(name)) {
                index = after(html, close + 1, "</" + name);
                index = index >= length ? length : Math.max(index, indexOf(html, index, '>') + 1);
                continue;
            }
            if (BLOCKS.contains(name)) text.append('\n');
            else if (CELLS.contains(name)) text.append(' ');
            index = close + 1;
        }
        return text.toString();
    }

    /** Lower-case element name, or empty when this '<' does not start a tag. */
    private static String tagName(CharSequence html, int start, int end) {
        var index = start < end && html.charAt(start) == '/' ? start + 1 : start;
        var name = new StringBuilder();
        while (index < end && Character.isLetterOrDigit(html.charAt(index)))
            name.append(html.charAt(index++));
        if (name.isEmpty() && index < end && html.charAt(index) == '!') return "!";
        return name.toString().toLowerCase(Locale.ROOT);
    }

    private static int entity(CharSequence html, int start, StringBuilder text) {
        var end = indexOf(html, start + 1, ';');
        if (end < 0 || end - start > 10) {
            text.append('&');
            return start + 1;
        }
        var name = html.subSequence(start + 1, end).toString();
        var decoded =
                switch (name) {
                    case "amp" -> "&";
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "quot" -> "\"";
                    case "apos", "#39" -> "'";
                    case "nbsp" -> " ";
                    default -> numeric(name);
                };
        if (decoded == null) {
            text.append('&');
            return start + 1;
        }
        text.append(decoded);
        return end + 1;
    }

    private static String numeric(String name) {
        if (!name.startsWith("#") || name.length() < 2) return null;
        try {
            var hex = name.charAt(1) == 'x' || name.charAt(1) == 'X';
            var value = Integer.parseInt(name.substring(hex ? 2 : 1), hex ? 16 : 10);
            // A null character stays rejected, like in uploaded text.
            if (value < 1 || !Character.isValidCodePoint(value)) return null;
            return Character.toString(value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static boolean startsWith(CharSequence html, int index, String prefix) {
        if (index + prefix.length() > html.length()) return false;
        for (var offset = 0; offset < prefix.length(); offset++)
            if (Character.toLowerCase(html.charAt(index + offset)) != prefix.charAt(offset))
                return false;
        return true;
    }

    /** Index just past the next case-insensitive occurrence of marker, or the end. */
    private static int after(CharSequence html, int from, String marker) {
        for (var index = from; index + marker.length() <= html.length(); index++)
            if (startsWith(html, index, marker)) return index + marker.length();
        return html.length();
    }

    private static int indexOf(CharSequence html, int from, char wanted) {
        for (var index = from; index < html.length(); index++)
            if (html.charAt(index) == wanted) return index;
        return -1;
    }
}
