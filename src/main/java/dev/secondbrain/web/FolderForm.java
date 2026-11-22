package dev.secondbrain.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/** Parses folder choices from forms: blank means unfiled; anything else must be a folder ID. */
final class FolderForm {
    private FolderForm() {}

    static Optional<UUID> choice(String value) {
        if (value == null || value.isEmpty()) return Optional.empty();
        try {
            return Optional.of(DocumentQueryForm.documentId(value));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Choose a folder from the list.");
        }
    }

    /** Library address for a folder, or the top of the library when unfiled. */
    static String library(Optional<UUID> folder) {
        return folder.map(id -> "/library?folder=" + id).orElse("/library");
    }
}
