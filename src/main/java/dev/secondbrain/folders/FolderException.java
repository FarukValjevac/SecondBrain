package dev.secondbrain.folders;

import java.util.Objects;

/** A folder request that cannot be applied; the message is safe to show to the user. */
public final class FolderException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Reason reason;

    public FolderException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").message(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_NAME("Use a folder name of 1 to 80 characters."),
        INVALID_COLOR("Choose one of the offered colours."),
        DUPLICATE_NAME("A folder with that name already exists here."),
        TOO_DEEP("Folders can be nested at most three levels deep."),
        TOO_MANY("You have reached the limit of 200 folders."),
        NOT_FOUND("That folder no longer exists.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }
}
