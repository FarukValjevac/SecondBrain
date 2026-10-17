package dev.secondbrain.documents;

import java.io.Serial;
import java.util.Objects;

/** Fixed failure vocabulary; the message never contains document content or a storage path. */
final class TextExtractionException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    private final Reason reason;

    TextExtractionException(Reason reason) {
        this(reason, null);
    }

    TextExtractionException(Reason reason, Throwable cause) {
        super(Objects.requireNonNull(reason, "reason").message, cause);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }

    enum Reason {
        INVALID_ENCODING("The document must use valid UTF-8 encoding."),
        EMPTY_TEXT("The document contains no readable text."),
        INVALID_CONTENT("The document contains a null character."),
        INPUT_TOO_LARGE("The document exceeds the extraction size limit."),
        TOO_MANY_LINES("The document exceeds the extraction line limit."),
        SOURCE_UNAVAILABLE("The original document could not be read."),
        CANCELLED("Text extraction was interrupted.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }
}
