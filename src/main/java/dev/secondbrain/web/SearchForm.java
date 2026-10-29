package dev.secondbrain.web;

import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchScope;

import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded view state may contain a blank query while browsing document choices. */
record SearchForm(String text, Scope scope, Set<UUID> documentIds, int page, Action action) {
    SearchForm {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(action, "action");
        documentIds = Set.copyOf(documentIds);
        if (page < 0
                || documentIds.size() > SearchScope.MAXIMUM_SELECTED_DOCUMENTS
                || text.length() > SearchQuery.MAXIMUM_CODE_POINTS * 2
                || text.codePointCount(0, text.length()) > SearchQuery.MAXIMUM_CODE_POINTS
                || text.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(text))
            throw new IllegalArgumentException("Invalid search form");
    }

    static SearchForm blank(int page) {
        return new SearchForm("", Scope.ALL, Set.of(), page, Action.SEARCH);
    }

    static SearchForm read(MultiValueMap<String, String> fields) {
        String text = single(fields, "q", null);
        var scope =
                switch (single(fields, "scope", null)) {
                    case "all" -> Scope.ALL;
                    case "selected" -> Scope.SELECTED;
                    default -> throw new IllegalArgumentException("Invalid document scope");
                };
        var values = fields.getOrDefault("documentId", java.util.List.of());
        if (values.size() > SearchScope.MAXIMUM_SELECTED_DOCUMENTS)
            throw new IllegalArgumentException("Too many document selections");
        var ids = new HashSet<UUID>();
        for (var value : values) {
            if (value.length() != 36)
                throw new IllegalArgumentException("Invalid document selection");
            var id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value))
                throw new IllegalArgumentException("Invalid document selection");
            ids.add(id);
        }
        int page = page(single(fields, "page", "0"));
        var action =
                switch (single(fields, "action", "search")) {
                    case "search" -> Action.SEARCH;
                    case "previous" -> {
                        page = Math.subtractExact(page, 1);
                        yield Action.BROWSE;
                    }
                    case "next" -> {
                        if (page == Integer.MAX_VALUE)
                            throw new IllegalArgumentException("Invalid page");
                        page++;
                        yield Action.BROWSE;
                    }
                    default -> throw new IllegalArgumentException("Invalid form action");
                };
        return new SearchForm(text, scope, ids, page, action);
    }

    SearchQuery toQuery() {
        // Checked boxes may remain selected while the user explicitly chooses the whole library.
        return new SearchQuery(
                text, scope == Scope.ALL ? SearchScope.all() : SearchScope.selected(documentIds));
    }

    static int page(String value) {
        if (value.length() > 10 || !value.matches("[0-9]+"))
            throw new IllegalArgumentException("Invalid document page");
        return Integer.parseInt(value);
    }

    private static String single(
            MultiValueMap<String, String> fields, String name, String fallback) {
        var values = fields.get(name);
        if (values == null && fallback != null) return fallback;
        if (values == null || values.size() != 1)
            throw new IllegalArgumentException("A single form value is required");
        return values.getFirst();
    }

    @Override
    public String toString() {
        return "SearchForm[scope=" + scope + ", page=" + page + "]";
    }

    enum Scope {
        ALL,
        SELECTED
    }

    enum Action {
        SEARCH,
        BROWSE
    }
}
