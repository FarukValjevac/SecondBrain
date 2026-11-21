package dev.secondbrain.web;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The outcome of one file in a multi-file browser upload; messages are fixed safe text. Public so
 * the library template can call {@link #isSaved()}.
 */
public record UploadResult(String name, Optional<UUID> documentId, String message) {
    public UploadResult {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(message, "message");
    }

    static UploadResult saved(String name, UUID documentId) {
        return new UploadResult(name, Optional.of(documentId), "Saved and queued");
    }

    static UploadResult rejected(String name, String message) {
        return new UploadResult(name, Optional.empty(), message);
    }

    public boolean isSaved() {
        return documentId.isPresent();
    }

    @Override
    public String toString() {
        // File names are private.
        return "UploadResult[saved=" + isSaved() + "]";
    }
}
