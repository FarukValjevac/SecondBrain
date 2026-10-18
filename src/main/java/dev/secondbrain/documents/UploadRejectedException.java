package dev.secondbrain.documents;

import java.io.Serial;
import java.util.Objects;

/** Fixed upload failure vocabulary; never includes the supplied filename or content. */
public final class UploadRejectedException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final Reason reason;

    public UploadRejectedException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").message());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        MISSING_FILENAME("Choose a file with a name."),
        INVALID_FILENAME(
                "The filename must contain no null characters and be at most 1024 UTF-8 bytes."),
        SINGLE_FILE_REQUIRED("Upload one file at a time."),
        EMPTY_FILE("The selected file is empty."),
        FILE_TOO_LARGE("The file exceeds the configured upload size limit."),
        UNSUPPORTED_TYPE("Only TXT, Markdown, and PDF files are supported.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }
}
