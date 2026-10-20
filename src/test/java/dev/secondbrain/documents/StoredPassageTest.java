package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

class StoredPassageTest {
    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final PreparedPassages.Location LOCATION =
            new PreparedPassages.Location(1, 0, 1, 4, OptionalInt.empty());

    @Test
    void identityIsStableWithinADocumentIndexAndSequenceAndIndependentOfText() {
        var version = new IndexingVersion(UUID.randomUUID(), 7, 2, AT);
        var passage = new PreparedPassages.Passage(1, "Note", LOCATION);
        var stored = StoredPassage.from(version, passage);
        assertThat(StoredPassage.from(version, passage)).isEqualTo(stored);
        assertThat(
                        StoredPassage.from(
                                        version, new PreparedPassages.Passage(1, "Text", LOCATION))
                                .passageId())
                .isEqualTo(stored.passageId());
        assertThat(
                        List.of(
                                StoredPassage.from(
                                                new IndexingVersion(version.documentId(), 8, 2, AT),
                                                passage)
                                        .passageId(),
                                StoredPassage.from(
                                                new IndexingVersion(UUID.randomUUID(), 7, 2, AT),
                                                passage)
                                        .passageId(),
                                StoredPassage.from(
                                                version,
                                                new PreparedPassages.Passage(2, "Note", LOCATION))
                                        .passageId()))
                .doesNotContain(stored.passageId())
                .doesNotHaveDuplicates();
    }

    @Test
    void rejectsInvalidStoredIdentityAndUnboundedOrInvalidTextWithoutEchoingContent() {
        var version = new IndexingVersion(UUID.randomUUID(), 7, 2, AT);
        var stored = StoredPassage.from(version, new PreparedPassages.Passage(1, "Note", LOCATION));
        assertThatThrownBy(
                        () ->
                                new StoredPassage(
                                        UUID.randomUUID(),
                                        version.documentId(),
                                        7,
                                        1,
                                        "Note",
                                        LOCATION))
                .isInstanceOf(IllegalArgumentException.class);
        for (var text : List.of(" ", "private-sentinel\0", "x".repeat(1201))) {
            assertThatThrownBy(
                            () ->
                                    new StoredPassage(
                                            stored.passageId(),
                                            version.documentId(),
                                            7,
                                            1,
                                            text,
                                            LOCATION))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("private-sentinel");
        }
    }
}
