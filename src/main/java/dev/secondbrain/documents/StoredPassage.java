package dev.secondbrain.documents;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Private staged content and its stable source identity. Never log this record. */
record StoredPassage(
        UUID passageId,
        UUID documentId,
        long indexingVersion,
        int sequence,
        String text,
        PreparedPassages.Location location) {
    StoredPassage {
        Objects.requireNonNull(passageId, "passageId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(location, "location");
        if (indexingVersion < 1
                || sequence < 1
                || sequence > PassageSplitter.MAXIMUM_PASSAGES
                || text.isBlank()
                || text.codePointCount(0, text.length()) > PassageSplitter.MAXIMUM_CODE_POINTS
                || text.indexOf('\0') >= 0
                || !passageId.equals(idFor(documentId, indexingVersion, sequence))) {
            throw new IllegalArgumentException("Invalid stored passage");
        }
    }

    static StoredPassage from(IndexingVersion version, PreparedPassages.Passage passage) {
        return new StoredPassage(
                idFor(version.documentId(), version.number(), passage.sequence()),
                version.documentId(),
                version.number(),
                passage.sequence(),
                passage.text(),
                passage.location());
    }

    private static UUID idFor(UUID documentId, long version, int sequence) {
        // Explicit namespace and delimited numeric keys; filenames/text never determine identity.
        return UUID.nameUUIDFromBytes(
                ("secondbrain:passage:v1:" + documentId + ":" + version + ":" + sequence)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
