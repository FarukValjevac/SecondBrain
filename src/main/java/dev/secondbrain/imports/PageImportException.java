package dev.secondbrain.imports;

import java.io.Serial;
import java.util.Objects;

/** Fixed, safe reasons; messages never contain the URL or response content. */
public final class PageImportException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final Reason reason;

    PageImportException(Reason reason) {
        this(reason, null);
    }

    PageImportException(Reason reason, Throwable cause) {
        super(Objects.requireNonNull(reason, "reason").message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_URL("Enter a full http:// or https:// web address."),
        BLOCKED_ADDRESS(
                "That address points to this computer or a private network and is not allowed."),
        UNREACHABLE("The page could not be reached."),
        TIMED_OUT("The page took too long to respond."),
        TOO_MANY_REDIRECTS("The page redirected too many times."),
        HTTP_ERROR("The server did not return the page."),
        UNSUPPORTED_CONTENT("Only HTML and plain text pages can be imported."),
        TOO_LARGE("The page exceeds the upload size limit.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }
}
