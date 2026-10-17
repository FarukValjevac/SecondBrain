package dev.secondbrain.documents;

import dev.secondbrain.documents.UploadRejectedException.Reason;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
final class DocumentUploadPolicy {
    private final long maximumUploadBytes;

    DocumentUploadPolicy(
            @Value("${secondbrain.documents.max-upload-size}") DataSize maximumUploadSize) {
        if (maximumUploadSize == null || maximumUploadSize.toBytes() < 1) {
            throw new IllegalArgumentException("Maximum upload size must be positive");
        }
        maximumUploadBytes = maximumUploadSize.toBytes();
    }

    DocumentType validate(String originalFilename, long sizeInBytes) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new UploadRejectedException(Reason.MISSING_FILENAME);
        }
        if (originalFilename.indexOf('\0') >= 0
                || originalFilename.getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw new UploadRejectedException(Reason.INVALID_FILENAME);
        }
        validateSize(sizeInBytes);

        var extension = extensionOf(originalFilename);
        return switch (extension) {
            case "txt" -> DocumentType.TXT;
            case "md", "markdown" -> DocumentType.MARKDOWN;
            default -> throw new UploadRejectedException(Reason.UNSUPPORTED_TYPE);
        };
    }

    void validateSize(long sizeInBytes) {
        if (sizeInBytes < 1) {
            throw new UploadRejectedException(Reason.EMPTY_FILE);
        }
        if (sizeInBytes > maximumUploadBytes) {
            throw new UploadRejectedException(Reason.FILE_TOO_LARGE);
        }
    }

    long maximumUploadBytes() {
        return maximumUploadBytes;
    }

    private static String extensionOf(String filename) {
        var separator = filename.lastIndexOf('.');
        if (separator < 0 || separator == filename.length() - 1) {
            return "";
        }
        return filename.substring(separator + 1).toLowerCase(Locale.ROOT);
    }
}
