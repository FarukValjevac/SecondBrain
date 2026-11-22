package dev.secondbrain.web;

import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchScope;

import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Bounded view state may contain a blank query while browsing document choices. */
record DocumentQueryForm(
        String text,
        Scope scope,
        Set<UUID> documentIds,
        Optional<UUID> folder,
        int page,
        Action action) {
    DocumentQueryForm {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(action, "action");
        documentIds = Set.copyOf(documentIds);
        if ((scope == Scope.FOLDER) != folder.isPresent())
            throw new IllegalArgumentException("A folder scope needs exactly one folder");
        if (page < 0
                || documentIds.size() > SearchScope.MAXIMUM_SELECTED_DOCUMENTS
                || text.length() > SearchQuery.MAXIMUM_CODE_POINTS * 2
                || text.codePointCount(0, text.length()) > SearchQuery.MAXIMUM_CODE_POINTS
                || text.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(text))
            throw new IllegalArgumentException("Invalid document query form");
    }

    static DocumentQueryForm blank(int page) {
        return new DocumentQueryForm(
                "", Scope.ALL, Set.of(), Optional.empty(), page, Action.SUBMIT);
    }

    /** A blank question or search limited to one folder and its subfolders. */
    static DocumentQueryForm folder(UUID folderId) {
        return new DocumentQueryForm(
                "",
                Scope.FOLDER,
                Set.of(),
                Optional.of(Objects.requireNonNull(folderId)),
                0,
                Action.SUBMIT);
    }

    /** A blank question limited to one document, as opened from that document's page. */
    static DocumentQueryForm selected(UUID documentId) {
        return new DocumentQueryForm(
                "",
                Scope.SELECTED,
                Set.of(Objects.requireNonNull(documentId)),
                Optional.empty(),
                0,
                Action.SUBMIT);
    }

    /** Parses a canonical document UUID; anything else is rejected rather than ignored. */
    static UUID documentId(String value) {
        if (value.length() != 36) throw new IllegalArgumentException("Invalid document selection");
        var id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value))
            throw new IllegalArgumentException("Invalid document selection");
        return id;
    }

    static DocumentQueryForm read(MultiValueMap<String, String> fields, String submitAction) {
        String text = single(fields, "q", null);
        // "folder" pairs with a separate folder field (scope picker); "folder:<id>" carries the
        // folder in the scope itself (the home page's single scope menu).
        var scopeValue = single(fields, "scope", null);
        Optional<UUID> folder = Optional.empty();
        final Scope scope;
        if (scopeValue.startsWith("folder:")) {
            scope = Scope.FOLDER;
            folder = Optional.of(documentId(scopeValue.substring("folder:".length())));
        } else {
            scope =
                    switch (scopeValue) {
                        case "all" -> Scope.ALL;
                        case "selected" -> Scope.SELECTED;
                        case "folder" -> Scope.FOLDER;
                        default -> throw new IllegalArgumentException("Invalid document scope");
                    };
            // The folder menu is always submitted; it only matters for the folder scope.
            if (scope == Scope.FOLDER)
                folder = Optional.of(documentId(single(fields, "folder", null)));
        }
        var values = fields.getOrDefault("documentId", java.util.List.of());
        if (values.size() > SearchScope.MAXIMUM_SELECTED_DOCUMENTS)
            throw new IllegalArgumentException("Too many document selections");
        var ids = new HashSet<UUID>();
        for (var value : values) ids.add(documentId(value));
        int page = page(single(fields, "page", "0"));
        var submittedAction = single(fields, "action", submitAction);
        var action =
                submittedAction.equals(submitAction)
                        ? Action.SUBMIT
                        : switch (submittedAction) {
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
        return new DocumentQueryForm(text, scope, ids, folder, page, action);
    }

    /**
     * The query for whole-library or selected scopes. A folder scope validates the text here and
     * searches the whole library only as a placeholder: callers must resolve it with {@link
     * FolderScope} before searching.
     */
    SearchQuery toQuery() {
        // Checked boxes may remain selected while the user explicitly chooses the whole library.
        return new SearchQuery(
                text,
                scope == Scope.SELECTED ? SearchScope.selected(documentIds) : SearchScope.all());
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
        return "DocumentQueryForm[scope=" + scope + ", page=" + page + "]";
    }

    enum Scope {
        ALL,
        SELECTED,
        FOLDER
    }

    enum Action {
        SUBMIT,
        BROWSE
    }
}
