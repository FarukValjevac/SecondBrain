package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.secondbrain.documents.DocumentUploadPolicy.DocumentType;
import dev.secondbrain.documents.DocumentUploadPolicy.Reason;
import dev.secondbrain.documents.DocumentUploadPolicy.RejectedUpload;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class DocumentUploadPolicyTest {
    private static final long MAXIMUM_BYTES = 10 * 1024 * 1024;
    private final DocumentUploadPolicy policy =
            new DocumentUploadPolicy(DataSize.ofBytes(MAXIMUM_BYTES));

    @Test
    void acceptsSupportedTextExtensionsCaseInsensitivelyWithinLimit() {
        assertThat(policy.validate("notes.txt", 1)).isEqualTo(DocumentType.TXT);
        assertThat(policy.validate("notes.MD", MAXIMUM_BYTES)).isEqualTo(DocumentType.MARKDOWN);
        assertThat(policy.validate("notes.markdown", 1)).isEqualTo(DocumentType.MARKDOWN);
    }

    @Test
    void rejectsMissingNameEmptyOversizedAndUnsupportedFilesWithSafeMessages() {
        assertRejected(null, 1, Reason.MISSING_FILENAME);
        assertRejected("notes.txt", 0, Reason.EMPTY_FILE);
        assertRejected("notes.txt", MAXIMUM_BYTES + 1, Reason.FILE_TOO_LARGE);
        assertRejected("notes.pdf", 1, Reason.UNSUPPORTED_TYPE);
        assertRejected("notes", 1, Reason.UNSUPPORTED_TYPE);
    }

    @Test
    void rejectsNonPositiveConfiguredLimit() {
        assertThatThrownBy(() -> new DocumentUploadPolicy(DataSize.ofBytes(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Maximum upload size must be positive");
    }

    private void assertRejected(String filename, long size, Reason expectedReason) {
        assertThatThrownBy(() -> policy.validate(filename, size))
                .isInstanceOf(RejectedUpload.class)
                .satisfies(
                        exception ->
                                assertThat(((RejectedUpload) exception).reason())
                                        .isEqualTo(expectedReason))
                .hasMessageNotContaining("private-sentinel");
    }
}
