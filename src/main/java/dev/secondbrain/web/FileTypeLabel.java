package dev.secondbrain.web;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Short, display-only type label derived from a document name ("notes.md" → "MD"). Public because
 * Thymeleaf expressions call it; it never reads the file and is not used for any decision.
 */
public final class FileTypeLabel {
    private static final Pattern EXTENSION = Pattern.compile("[A-Za-z0-9]{1,5}");

    public String of(String name) {
        if (name == null) return "Document";
        var dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "Document";
        var extension = name.substring(dot + 1);
        return EXTENSION.matcher(extension).matches()
                ? extension.toUpperCase(Locale.ROOT)
                : "Document";
    }
}
