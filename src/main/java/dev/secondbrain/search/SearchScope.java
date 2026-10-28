package dev.secondbrain.search;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** An empty explicit selection never means the whole library. */
public sealed interface SearchScope
        permits SearchScope.AllDocuments, SearchScope.SelectedDocuments {
    int MAXIMUM_SELECTED_DOCUMENTS = 100;

    static SearchScope all() {
        return new AllDocuments();
    }

    static SearchScope selected(Set<UUID> documentIds) {
        return new SelectedDocuments(documentIds);
    }

    record AllDocuments() implements SearchScope {}

    record SelectedDocuments(Set<UUID> documentIds) implements SearchScope {
        public SelectedDocuments {
            Objects.requireNonNull(documentIds, "documentIds");
            if (documentIds.size() > MAXIMUM_SELECTED_DOCUMENTS)
                throw new IllegalArgumentException("Too many selected documents");
            documentIds = Set.copyOf(documentIds);
        }

        @Override
        public String toString() {
            return "SelectedDocuments[count=" + documentIds.size() + "]";
        }
    }
}
