package dev.secondbrain.documents;

import java.io.Serial;
import java.util.Objects;

/** Safe adapter failures; PDF library exception details remain internal. */
final class PdfExtractionException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final Reason reason;

    PdfExtractionException(Reason reason) {
        this(reason, null);
    }

    PdfExtractionException(Reason reason, Throwable cause) {
        super(Objects.requireNonNull(reason, "reason").message, cause);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }

    enum Reason {
        SOURCE_UNAVAILABLE("The PDF could not be read."),
        UNREADABLE("The PDF is corrupt or unsupported."),
        ENCRYPTED("Encrypted PDFs are not supported."),
        NO_TEXT("The PDF contains no selectable text. OCR is not supported."),
        INPUT_TOO_LARGE("The PDF exceeds the input size limit."),
        PAGE_LIMIT("The PDF exceeds the page limit."),
        TEXT_LIMIT("The PDF exceeds the extracted text limit."),
        WORK_LIMIT("The PDF exceeds the processing work limit."),
        TIME_LIMIT("PDF extraction exceeded its time budget."),
        CANCELLED("PDF extraction was interrupted.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }
}
