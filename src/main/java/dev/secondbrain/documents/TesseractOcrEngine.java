package dev.secondbrain.documents;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

/**
 * Runs the local {@code tesseract} program once per page: the page image and the recognised text go
 * through private temporary files that are deleted afterwards. No shell is involved and the
 * arguments are fixed, so document content can never become a command.
 */
final class TesseractOcrEngine implements OcrEngine {
    private static final int MAXIMUM_TEXT_BYTES = 1024 * 1024;
    private final String command;
    private final String languages;
    private final Duration timeout;

    TesseractOcrEngine(String command, String languages, Duration timeout) {
        this.command = Objects.requireNonNull(command, "command");
        this.languages = Objects.requireNonNull(languages, "languages");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public String recognise(BufferedImage page) throws InterruptedException {
        Objects.requireNonNull(page, "page");
        Path directory = null;
        try {
            directory =
                    Files.createTempDirectory(
                            "secondbrain-ocr-",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
            var image = directory.resolve("page.png");
            var text = directory.resolve("page.txt");
            if (!ImageIO.write(page, "png", image.toFile()))
                throw new PageFailed("Page image could not be written", null);
            final Process process;
            try {
                process =
                        new ProcessBuilder(
                                        List.of(
                                                command,
                                                image.toString(),
                                                "stdout",
                                                "-l",
                                                languages))
                                .redirectOutput(text.toFile())
                                .redirectError(ProcessBuilder.Redirect.DISCARD)
                                .start();
            } catch (IOException missing) {
                throw new Unavailable("Tesseract could not be started", missing);
            }
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new PageFailed("Text recognition timed out", null);
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                throw interrupted;
            }
            // A non-zero exit means Tesseract or its language data is not usable.
            if (process.exitValue() != 0)
                throw new Unavailable("Tesseract failed with exit " + process.exitValue(), null);
            if (Files.size(text) > MAXIMUM_TEXT_BYTES)
                throw new PageFailed("Recognised text exceeds the page limit", null);
            return Files.readString(text, StandardCharsets.UTF_8);
        } catch (IOException unavailable) {
            throw new PageFailed("Temporary OCR files could not be used", unavailable);
        } finally {
            delete(directory);
        }
    }

    private static void delete(Path directory) {
        if (directory == null) return;
        try (var files = Files.list(directory)) {
            for (var file : (Iterable<Path>) files::iterator) Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best effort: the operating system clears its temporary directory.
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // Best effort, as above.
        }
    }
}
