package dev.secondbrain.folders;

import java.util.Locale;

/** A closed palette, so folder colours always meet the design system's contrast rules. */
public enum FolderColor {
    VIOLET,
    BLUE,
    TEAL,
    GREEN,
    AMBER,
    ORANGE,
    ROSE,
    SLATE;

    /** Lower-case token used in CSS class names and form values. */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String label() {
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }

    public static FolderColor parse(String value) {
        if (value == null) throw new FolderException(FolderException.Reason.INVALID_COLOR);
        for (var color : values()) if (color.token().equals(value)) return color;
        throw new FolderException(FolderException.Reason.INVALID_COLOR);
    }
}
