package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

/**
 * Process handling with small fake "tesseract" scripts; the real engine runs in TesseractLiveIT.
 */
class TesseractOcrEngineTest {
    @TempDir private Path directory;
    private final BufferedImage page = new BufferedImage(20, 20, BufferedImage.TYPE_BYTE_GRAY);

    private String script(String body) throws IOException {
        var script = directory.resolve("fake-tesseract");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script.toString();
    }

    @Test
    void passesTheImageAndLanguagesAndReturnsRecognisedText() throws Exception {
        // $1 is the page image, then "stdout -l <languages>"; the script proves the image exists.
        var command =
                script("test -s \"$1\" && printf 'Seite eins\\n%s %s %s' \"$2\" \"$3\" \"$4\"");
        var text =
                new TesseractOcrEngine(command, "deu+eng", Duration.ofSeconds(5)).recognise(page);
        assertThat(text).isEqualTo("Seite eins\nstdout -l deu+eng");
    }

    @Test
    void aMissingProgramOrLanguageDataIsReportedAsUnavailable() {
        assertThatThrownBy(
                        () ->
                                new TesseractOcrEngine(
                                                directory.resolve("missing").toString(),
                                                "deu",
                                                Duration.ofSeconds(5))
                                        .recognise(page))
                .isInstanceOf(OcrEngine.Unavailable.class);
        assertThatThrownBy(
                        () ->
                                new TesseractOcrEngine(
                                                script("exit 1"), "deu", Duration.ofSeconds(5))
                                        .recognise(page))
                .isInstanceOf(OcrEngine.Unavailable.class);
    }

    @Test
    void aSlowPageIsStoppedAndCanBeRetried() throws Exception {
        var command = script("sleep 10");
        long started = System.nanoTime();
        assertThatThrownBy(
                        () ->
                                new TesseractOcrEngine(command, "deu", Duration.ofMillis(300))
                                        .recognise(page))
                .isInstanceOf(OcrEngine.PageFailed.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void rejectsUnsafeSettings() {
        assertThatThrownBy(
                        () ->
                                new OcrProperties(
                                        true,
                                        "tesseract",
                                        "deu; rm",
                                        300,
                                        Duration.ofSeconds(90),
                                        10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new OcrProperties(true, " ", "deu", 300, Duration.ofSeconds(90), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        new OcrProperties(
                                        true,
                                        "tesseract",
                                        "deu+eng",
                                        300,
                                        Duration.ofSeconds(90),
                                        1000)
                                .languages())
                .isEqualTo("deu+eng");
    }
}
