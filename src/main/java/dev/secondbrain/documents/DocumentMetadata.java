package dev.secondbrain.documents;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

record DocumentMetadata(
        UUID documentId,
        String originalName,
        DocumentType documentType,
        UUID storageId,
        String contentSha256,
        long originalSizeBytes,
        long processingVersion,
        ExtractionState extractionState,
        Instant createdAt,
        Instant updatedAt) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    DocumentMetadata {
        Objects.requireNonNull(documentId, "documentId");
        if (originalName == null
                || originalName.isBlank()
                || originalName.indexOf('\0') >= 0
                || originalName.getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw new IllegalArgumentException(
                    "Original name must be non-empty and at most 1024 bytes");
        }
        Objects.requireNonNull(documentType, "documentType");
        Objects.requireNonNull(storageId, "storageId");
        if (contentSha256 == null || !SHA_256.matcher(contentSha256).matches()) {
            throw new IllegalArgumentException(
                    "Content checksum must be a lowercase SHA-256 value");
        }
        if (originalSizeBytes < 1 || processingVersion < 1) {
            throw new IllegalArgumentException("Size and processing version must be positive");
        }
        Objects.requireNonNull(extractionState, "extractionState");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
