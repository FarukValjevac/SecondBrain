package dev.secondbrain.documents;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One index generation built from an immutable extracted-text version. */
record IndexingVersion(UUID documentId, long number, long extractionVersion, Instant createdAt) {
    IndexingVersion {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (number < 1 || extractionVersion < 1) {
            throw new IllegalArgumentException("Index and extraction versions must be positive");
        }
    }
}
