package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.TestDatabase;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Scanned PDFs are recognised page by page with durable progress; the engine is a test fake. */
class ScannedPdfIT {
    private static final TestDatabase POSTGRES = TestDatabase.create();
    @TempDir private Path storage;

    @AfterAll
    static void close() {
        POSTGRES.close();
    }

    /** Returns "Seite N" in call order; can be told to fail once or to be unavailable. */
    static final class FakeOcr implements OcrEngine {
        final CopyOnWriteArrayList<Integer> calls = new CopyOnWriteArrayList<>();
        final AtomicReference<String> mode = new AtomicReference<>("ok");
        private int recognised;

        @Override
        public synchronized String recognise(BufferedImage page) {
            assertThat(page.getWidth()).isGreaterThan(1000);
            calls.add(calls.size() + 1);
            if (mode.get().equals("unavailable")) throw new Unavailable("not installed", null);
            if (mode.compareAndSet("fail-once", "ok")) throw new PageFailed("timed out", null);
            recognised++;
            return "\fSeite " + recognised + "\nmit Text";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FakeOcrConfiguration {
        @Bean
        @org.springframework.context.annotation.Primary
        FakeOcr fakeOcr() {
            return new FakeOcr();
        }
    }

    @Test
    void recognisesEveryPageKeepsPageNumbersAndResumesAfterAFailedPage() throws Exception {
        try (var context = start()) {
            var ocr = context.getBean(FakeOcr.class);
            ocr.mode.set("ok");
            var id = upload(context, PdfFixtures.scanned("Erste Seite", "Zweite Seite"));
            awaitState(context, id, "EXTRACTED");
            var view = context.getBean(DocumentInspectionService.class).find(id).orElseThrow();
            assertThat(view.lines())
                    .extracting(DocumentInspectionService.Line::text)
                    .containsExactly("Seite 1", "mit Text", "Seite 2", "mit Text");
            assertThat(view.lines())
                    .extracting(line -> line.pageNumber().getAsInt())
                    .containsExactly(1, 1, 2, 2);
            // Progress rows are removed once the text is stored.
            assertThat(pages(context, id)).isZero();
            assertThat(context.getBean(DocumentInspectionService.class).ocrProgress(id)).isEmpty();

            // A failed page is retried; pages already saved are not recognised again.
            int before = ocr.calls.size();
            ocr.mode.set("fail-once");
            var second = upload(context, PdfFixtures.scanned("Eins", "Zwei", "Drei"));
            awaitState(context, second, "EXTRACTED");
            assertThat(ocr.calls.size() - before).isEqualTo(4);
        }
    }

    @Test
    void missingTesseractFailsWithAnActionableMessageAndCanBeRetried() throws Exception {
        try (var context = start()) {
            context.getBean(FakeOcr.class).mode.set("unavailable");
            var id = upload(context, PdfFixtures.scanned("Seite"));
            awaitState(context, id, "FAILED");
            assertThat(
                            context.getBean(DocumentInspectionService.class)
                                    .find(id)
                                    .orElseThrow()
                                    .message())
                    .contains("brew install tesseract tesseract-lang", "Retry processing");
            assertThat(
                            context.getBean(DocumentProcessingService.class)
                                    .read(java.util.Set.of(id))
                                    .get(id)
                                    .retry())
                    .isPresent();
        }
    }

    private static int pages(ConfigurableApplicationContext context, UUID id) {
        return context.getBean(JdbcClient.class)
                .sql("SELECT count(*) FROM document_ocr_pages WHERE document_id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private static UUID upload(ConfigurableApplicationContext context, byte[] pdf)
            throws Exception {
        return context.getBean(DocumentAcceptanceService.class)
                .accept("scan.pdf", pdf.length, new ByteArrayInputStream(pdf), UUID.randomUUID())
                .documentId();
    }

    private static void awaitState(ConfigurableApplicationContext context, UUID id, String state) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(
                                                context.getBean(JdbcClient.class)
                                                        .sql(
                                                                "SELECT extraction_state FROM documents WHERE document_id = :id")
                                                        .param("id", id)
                                                        .query(String.class)
                                                        .single())
                                        .isEqualTo(state));
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(
                        SecondBrainApplication.class, FakeOcrConfiguration.class)
                .web(WebApplicationType.NONE)
                .run(
                        POSTGRES.arguments(
                                "--secondbrain.telemetry.otlp-endpoint=",
                                "--secondbrain.documents.cleanup.enabled=false",
                                "--secondbrain.documents.indexing.enabled=false",
                                "--secondbrain.events.polling-enabled=true",
                                "--secondbrain.events.poll-interval=50ms",
                                "--secondbrain.events.initial-backoff=100ms",
                                "--secondbrain.events.max-backoff=200ms",
                                "--secondbrain.documents.storage-directory=" + storage));
    }
}
