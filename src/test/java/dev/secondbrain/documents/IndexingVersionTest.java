package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.UUID;

class IndexingVersionTest {
    @ParameterizedTest
    @CsvSource({"0,1", "-1,1", "1,0", "1,-1"})
    void rejectsNonPositiveVersions(long index, long extraction) {
        assertThatThrownBy(
                        () ->
                                new IndexingVersion(
                                        UUID.randomUUID(), index, extraction, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Index and extraction versions must be positive");
    }

    @Test
    void requiresDocumentIdentityAndCreationTime() {
        assertThatThrownBy(() -> new IndexingVersion(null, 1, 1, Instant.EPOCH))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IndexingVersion(UUID.randomUUID(), 1, 1, null))
                .isInstanceOf(NullPointerException.class);
    }
}
