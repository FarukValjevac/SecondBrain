package dev.secondbrain.folders;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One folder. Names are user text: never log them. */
public record Folder(
        UUID folderId, Optional<UUID> parentId, String name, FolderColor color, int depth) {
    public static final int MAXIMUM_DEPTH = 3;
    public static final int MAXIMUM_NAME_LENGTH = 80;

    public Folder {
        Objects.requireNonNull(folderId, "folderId");
        Objects.requireNonNull(parentId, "parentId");
        Objects.requireNonNull(color, "color");
        name = name(name);
        if (depth < 1 || depth > MAXIMUM_DEPTH || parentId.isEmpty() != (depth == 1))
            throw new IllegalArgumentException("Invalid folder depth");
    }

    /** Trims and validates a folder name; control characters are never accepted. */
    public static String name(String value) {
        if (value == null) throw new FolderException(FolderException.Reason.INVALID_NAME);
        var trimmed = value.strip();
        if (trimmed.isEmpty()
                || trimmed.codePointCount(0, trimmed.length()) > MAXIMUM_NAME_LENGTH
                || trimmed.codePoints().anyMatch(Character::isISOControl))
            throw new FolderException(FolderException.Reason.INVALID_NAME);
        return trimmed;
    }

    /** Nesting allows one more level below this folder. */
    public boolean canHaveChildren() {
        return depth < MAXIMUM_DEPTH;
    }

    @Override
    public String toString() {
        return "Folder[id=" + folderId + ", depth=" + depth + "]";
    }
}
