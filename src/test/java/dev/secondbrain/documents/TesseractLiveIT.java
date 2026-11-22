package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;

/**
 * The real Tesseract program on a rendered scanned page. Runs when {@code -Docr.tesseract=true} is
 * set (CI installs Tesseract and sets it); locally it needs {@code brew install tesseract
 * tesseract-lang}.
 */
@EnabledIfSystemProperty(named = "ocr.tesseract", matches = "true")
class TesseractLiveIT {
    @Test
    void recognisesPrintedGermanTextFromARenderedScan() throws Exception {
        var pdf = PdfFixtures.scanned("Psychosoziale Interventionen\nBeziehung und Beratung");
        try (var images = PdfPageImages.open(pdf)) {
            assertThat(images.pageCount()).isEqualTo(1);
            var text =
                    new TesseractOcrEngine(
                                    System.getProperty("ocr.command", "tesseract"),
                                    "deu+eng",
                                    Duration.ofSeconds(60))
                            .recognise(images.render(1, 300));
            assertThat(text).contains("Interventionen", "Beratung");
        }
    }
}
