package dev.secondbrain.documents;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Text recognition for scanned PDFs with a locally installed Tesseract. Page images and text stay
 * on this computer.
 */
@ConfigurationProperties("secondbrain.ocr")
record OcrProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("tesseract") String command,
        @DefaultValue("deu+eng") String languages,
        @DefaultValue("300") int dpi,
        @DefaultValue("90s") Duration pageTimeout,
        @DefaultValue("1000") int maximumPages) {
    private static final Pattern LANGUAGES = Pattern.compile("[a-z_]{3,16}(\\+[a-z_]{3,16}){0,5}");

    OcrProperties {
        if (command == null || command.isBlank() || command.indexOf('\0') >= 0)
            throw new IllegalArgumentException("OCR command must be set");
        if (languages == null || !LANGUAGES.matcher(languages).matches())
            throw new IllegalArgumentException("OCR languages must look like deu+eng");
        if (dpi < 100 || dpi > 600) throw new IllegalArgumentException("OCR dpi must be 100–600");
        if (pageTimeout == null
                || pageTimeout.compareTo(Duration.ofSeconds(5)) < 0
                || pageTimeout.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("OCR page timeout must be 5s–10m");
        if (maximumPages < 1 || maximumPages > 1000)
            throw new IllegalArgumentException("OCR page limit must be 1–1000");
    }
}
