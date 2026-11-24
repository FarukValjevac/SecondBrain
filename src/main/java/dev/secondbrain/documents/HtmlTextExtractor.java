package dev.secondbrain.documents;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * Latin-1 entities in code point order (160-255): the umlauts, accents and symbols that
     * European pages commonly write as names.
     */
    private static final String LATIN_1 =
            "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml";

    /** Named entities beyond Latin-1 that appear in ordinary text. */
    private static final Map<String, String> NAMED = named();

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
                    default -> NAMED.getOrDefault(name, numeric(name));
                };
        if (decoded == null) {
            text.append('&');
            return start + 1;
        }
        text.append(decoded);
        return end + 1;
    }

    private static Map<String, String> named() {
        var names = new HashMap<String, String>();
        var latin = LATIN_1.split(" ");
        for (int index = 0; index < latin.length; index++)
            names.put(latin[index], Character.toString(160 + index));
        String[][] typographic = {
            {"OElig", "\u0152"},
            {"oelig", "\u0153"},
            {"Scaron", "\u0160"},
            {"scaron", "\u0161"},
            {"Yuml", "\u0178"},
            {"fnof", "\u0192"},
            {"circ", "\u02C6"},
            {"tilde", "\u02DC"},
            {"ensp", " "},
            {"emsp", " "},
            {"thinsp", " "},
            {"zwnj", ""},
            {"zwj", ""},
            {"lrm", ""},
            {"rlm", ""},
            {"ndash", "\u2013"},
            {"mdash", "\u2014"},
            {"lsquo", "\u2018"},
            {"rsquo", "\u2019"},
            {"sbquo", "\u201A"},
            {"ldquo", "\u201C"},
            {"rdquo", "\u201D"},
            {"bdquo", "\u201E"},
            {"dagger", "\u2020"},
            {"Dagger", "\u2021"},
            {"bull", "\u2022"},
            {"hellip", "\u2026"},
            {"permil", "\u2030"},
            {"prime", "\u2032"},
            {"Prime", "\u2033"},
            {"lsaquo", "\u2039"},
            {"rsaquo", "\u203A"},
            {"euro", "\u20AC"},
            {"trade", "\u2122"},
            {"larr", "\u2190"},
            {"rarr", "\u2192"},
            {"harr", "\u2194"},
            {"minus", "\u2212"},
            {"le", "\u2264"},
            {"ge", "\u2265"},
            {"ne", "\u2260"},
            {"asymp", "\u2248"},
            {"infin", "\u221E"}
        };
        for (var entry : typographic) names.put(entry[0], entry[1]);
        return Map.copyOf(names);
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
