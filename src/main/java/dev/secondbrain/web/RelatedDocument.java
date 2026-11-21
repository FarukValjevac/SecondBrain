package dev.secondbrain.web;

import dev.secondbrain.search.SearchResult;

import java.util.Objects;
import java.util.UUID;

/** A similar document and its closest passage, as shown on another document's page. */
record RelatedDocument(UUID documentId, String name, SearchResult.Match match) {
    RelatedDocument {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(match, "match");
    }

    @Override
    public String toString() {
        // Names and passage text are private document content.
        return "RelatedDocument[documentId=" + documentId + "]";
    }
}
