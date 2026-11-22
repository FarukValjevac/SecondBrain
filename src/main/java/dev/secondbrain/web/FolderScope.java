package dev.secondbrain.web;

import dev.secondbrain.folders.FolderException;
import dev.secondbrain.folders.FolderService;
import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchScope;

import org.springframework.beans.factory.ObjectProvider;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Turns a folder scope into the existing selected-documents scope: every visible document in the
 * folder and its subfolders. Retrieval, citation checks and visibility rules therefore stay exactly
 * the same as for a hand-picked selection, including its 100-document limit.
 */
final class FolderScope {
    private FolderScope() {}

    static SearchQuery resolve(
            DocumentQueryForm form, SearchQuery validated, ObjectProvider<FolderService> folders) {
        Objects.requireNonNull(validated, "validated");
        if (form.scope() != DocumentQueryForm.Scope.FOLDER) return validated;
        var ids = documents(form.folder().orElseThrow(), folders);
        if (ids.size() > SearchScope.MAXIMUM_SELECTED_DOCUMENTS)
            throw new Problem(Problem.Reason.TOO_LARGE);
        return new SearchQuery(
                validated.text(), SearchScope.selected(new HashSet<>(ids)), validated.limit());
    }

    /** Up to 101 documents, so callers can tell when a folder exceeds the selection limit. */
    static List<UUID> documents(UUID folder, ObjectProvider<FolderService> folders) {
        var service = folders.getIfAvailable();
        if (service == null) throw new Problem(Problem.Reason.MISSING);
        final List<UUID> ids;
        try {
            ids = service.documentsWithin(folder, SearchScope.MAXIMUM_SELECTED_DOCUMENTS + 1);
        } catch (FolderException missing) {
            throw new Problem(Problem.Reason.MISSING);
        }
        if (ids.isEmpty()) throw new Problem(Problem.Reason.EMPTY);
        return ids;
    }

    /** Why a folder cannot be used as a scope; each maps to a fixed user message. */
    static final class Problem extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Reason reason;

        Problem(Reason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }

        enum Reason {
            MISSING,
            EMPTY,
            TOO_LARGE
        }
    }
}
