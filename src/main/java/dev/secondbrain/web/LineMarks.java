package dev.secondbrain.web;

import java.util.Map;

/**
 * The CSS classes of one source line on the document page: {@code cited} inside a cited range (from
 * a source link) and {@code hl hl-<colour>} for the latest highlight covering it. Public because
 * the template calls {@link #of(int)}.
 */
public final class LineMarks {
    private final int citedFrom;
    private final int citedTo;
    private final Map<Integer, String> highlighted;

    LineMarks(int citedFrom, int citedTo, Map<Integer, String> highlighted) {
        this.citedFrom = citedFrom;
        this.citedTo = citedTo;
        this.highlighted = Map.copyOf(highlighted);
    }

    public String of(int line) {
        boolean cited = line >= citedFrom && line <= citedTo;
        var colour = highlighted.get(line);
        if (colour == null) return cited ? "cited" : "";
        return (cited ? "cited " : "") + "hl hl-" + colour;
    }
}
