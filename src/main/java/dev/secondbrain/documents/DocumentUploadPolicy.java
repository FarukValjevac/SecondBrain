package dev.secondbrain.documents;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.io.Serial;
import java.util.Locale;

@Component
final class DocumentUploadPolicy {
    private final long maximumUploadBytes;

    DocumentUploadPolicy(
            @Value("${secondbrain.documents.max-upload-size:10MB}") DataSize maximumUploadSize) {
        if (maximumUploadSize == null || maximumUploadSize.toBytes() < 1) {
            throw new IllegalArgumentException("Maximum upload size must be positive");
        }
        maximumUploadBytes = maximumUploadSize.toBytes();
    }

    DocumentType validate(String originalFilename, long sizeInBytes) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new RejectedUpload(Reason.MISSING_FILENAME);
        }
        if (sizeInBytes < 1) {
            throw new RejectedUpload(Reason.EMPTY_FILE);
        }
        if (sizeInBytes > maximumUploadBytes) {
            throw new RejectedUpload(Reason.FILE_TOO_LARGE);
        }

        var extension = extensionOf(originalFilename);
        return switch (extension) {
            case "txt" -> DocumentType.TXT;
            case "md", "markdown" -> DocumentType.MARKDOWN;
            default -> throw new RejectedUpload(Reason.UNSUPPORTED_TYPE);
        };
    }

    private static String extensionOf(String filename) {
        var separator = filename.lastIndexOf('.');
        if (separator < 0 || separator == filename.length() - 1) {
            return "";
        }
        return filename.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    enum DocumentType {
        TXT,
        MARKDOWN
    }

    enum Reason {
        MISSING_FILENAME("Choose a file with a name."),
        EMPTY_FILE("The selected file is empty."),
        FILE_TOO_LARGE("The file exceeds the configured upload size limit."),
        UNSUPPORTED_TYPE("Only TXT and Markdown files are supported.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }

    static final class RejectedUpload extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;

        private final Reason reason;

        RejectedUpload(Reason reason) {
            super(reason.message());
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }
    }
}
