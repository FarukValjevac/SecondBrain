package dev.secondbrain.search;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Application input, independent of HTTP and providers. Never log the query text. */
public record SearchQuery(String text, SearchScope scope, int limit) {
    // Matches the existing embedding adapter's per-input application budget.
    public static final int MAXIMUM_CODE_POINTS = 1200;
    public static final int MAXIMUM_RESULTS = 20;
    public static final int DEFAULT_RESULTS = 5;

    public SearchQuery(String text, SearchScope scope) {
        this(text, scope, DEFAULT_RESULTS);
    }

    public SearchQuery {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(scope, "scope");
        if (text.length() > MAXIMUM_CODE_POINTS * 2
                || text.codePointCount(0, text.length()) > MAXIMUM_CODE_POINTS
                || text.isBlank()
                || text.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(text))
            throw new IllegalArgumentException(
                    "Query must contain valid text within the search limit");
        if (limit < 1 || limit > MAXIMUM_RESULTS)
            throw new IllegalArgumentException("Search result limit is out of range");
        text = text.strip();
    }

    @Override
    public String toString() {
        return "SearchQuery[scope=" + scope + ", limit=" + limit + "]";
    }
}
