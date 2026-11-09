package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.DocumentInspectionService.LibraryPage;

import org.springframework.ui.Model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Shared query/picker presentation only; no retrieval, generation or provider access. */
final class DocumentQueryView {
    private DocumentQueryView() {}

    static void restore(DocumentQueryForm form, Model model) {
        model.addAttribute("queryText", form.text());
        model.addAttribute(
                "scope", form.scope() == DocumentQueryForm.Scope.ALL ? "all" : "selected");
        model.addAttribute("documentIds", form.documentIds().stream().sorted().toList());
        model.addAttribute("documentPage", new LibraryPage(List.of(), form.page(), false));
        model.addAttribute(
                "otherSelections",
                form.documentIds().stream()
                        .sorted()
                        .map(id -> new Choice(id, "Selected document"))
                        .toList());
        model.addAttribute("pickerAvailable", false);
    }

    static void populate(DocumentQueryForm form, DocumentInspectionService documents, Model model) {
        var page = documents.list(form.page());
        var visibleIds =
                page.documents().stream()
                        .map(document -> document.documentId())
                        .collect(Collectors.toSet());
        var otherIds =
                form.documentIds().stream()
                        .filter(id -> !visibleIds.contains(id))
                        .collect(Collectors.toSet());
        var names = documents.names(otherIds);
        var others =
                otherIds.stream()
                        .sorted()
                        .map(id -> new Choice(id, names.getOrDefault(id, "Document unavailable")))
                        .toList();
        model.addAttribute("documentPage", page);
        model.addAttribute("otherSelections", others);
        model.addAttribute("pickerAvailable", true);
    }

    record Choice(UUID documentId, String name) {
        Choice {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String toString() {
            return "DocumentChoice";
        }
    }
}
