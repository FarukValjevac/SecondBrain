package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.secondbrain.documents.PdfExtractionException.Reason;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

class PdfTextExtractorTest {
    private static final long INPUT_LIMIT = 1024 * 1024;
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void preservesPhysicalPageNumbersBlankPagesAndLiteralUnicodeText() throws Exception {
        var bytes = pdf("Café study notes", "", "<script>Reference text</script>");
        var pages = new PdfTextExtractor(INPUT_LIMIT).extract(new ByteArrayInputStream(bytes));
        assertThat(pages).extracting(PdfTextExtractor.Page::number).containsExactly(1, 2, 3);
        assertThat(pages.get(0).text()).contains("Café study notes");
        assertThat(pages.get(1).text()).isEmpty();
        assertThat(pages.get(2).text()).contains("<script>Reference text</script>");
        assertThatThrownBy(() -> pages.add(new PdfTextExtractor.Page(4, "extra")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "sentinel-private-password"})
    void rejectsEncryptedDocumentsEvenWhenTheyOpenWithoutAPassword(String password)
            throws Exception {
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            var protection =
                    new StandardProtectionPolicy("owner", password, new AccessPermission());
            protection.setEncryptionKeyLength(128);
            document.protect(protection);
            assertFailure(new PdfTextExtractor(INPUT_LIMIT), save(document), Reason.ENCRYPTED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "sentinel-private-not-a-pdf", "%PDF-1.7\ntruncated"})
    void reportsMalformedOrTruncatedPdfWithoutExposingItsContents(String content) {
        var bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertFailure(new PdfTextExtractor(INPUT_LIMIT), bytes, Reason.UNREADABLE);
    }

    @Test
    void rejectsBlankDocumentsAndImageOnlyPagesWithoutAttemptingOcr() throws Exception {
        assertFailure(new PdfTextExtractor(INPUT_LIMIT), pdf("", ""), Reason.NO_TEXT);
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            var image =
                    LosslessFactory.createFromImage(
                            document, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
            try (var content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 10, 10);
            }
            assertFailure(new PdfTextExtractor(INPUT_LIMIT), save(document), Reason.NO_TEXT);
        }
    }

    @Test
    void acceptsTheExactInputLimitAndReadsOnlyOneOverflowByte() throws Exception {
        var bytes = pdf("Size boundary");
        assertThat(new PdfTextExtractor(bytes.length).extract(new ByteArrayInputStream(bytes)))
                .hasSize(1);
        var input = new ByteArrayInputStream(bytes);
        assertThatThrownBy(() -> new PdfTextExtractor(32).extract(input))
                .isInstanceOfSatisfying(
                        PdfExtractionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.INPUT_TOO_LARGE));
        assertThat(input.available()).isEqualTo(bytes.length - 33);
    }

    @Test
    void boundsPagesOutputAcrossPagesAndBufferedGlyphs() throws Exception {
        assertFailure(extractor(1, 100, 100), pdf("one", "two"), Reason.PAGE_LIMIT);
        assertFailure(extractor(3, 1, 100), pdf("a", "b"), Reason.TEXT_LIMIT);
        assertFailure(extractor(3, 5, 100), pdf("longer than the glyph budget"), Reason.TEXT_LIMIT);
        // Positioned glyphs can generate a space that was not present in the source strings.
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(30, 700);
                content.showTextWithPositioning(new Object[] {"a", -500f, "b"});
                content.endText();
            }
            assertFailure(extractor(3, 2, 100), save(document), Reason.TEXT_LIMIT);
        }
    }

    @Test
    void boundsGraphicsOperatorsEvenWhenTheyProduceNoText() throws Exception {
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                for (int index = 0; index < 10; index++) {
                    content.addRect(index, index, 1, 1);
                    content.fill();
                }
            }
            assertFailure(extractor(3, 100, 3), save(document), Reason.WORK_LIMIT);
        }
    }

    @Test
    void stopsWhenTheCooperativeDeadlineExpiresDuringParsing() throws Exception {
        var now = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock clock =
                new Clock() {
                    @Override
                    public ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return now.get();
                    }
                };
        var bytes = pdf("Deadline");
        var extractor = new PdfTextExtractor(limits(3, 100, 100), clock);
        var input =
                new ByteArrayInputStream(bytes) {
                    @Override
                    public synchronized int read(byte[] buffer, int offset, int length) {
                        int count = super.read(buffer, offset, length);
                        if (count < 0) now.set(NOW.plusSeconds(11));
                        return count;
                    }
                };
        assertThatThrownBy(() -> extractor.extract(input))
                .isInstanceOfSatisfying(
                        PdfExtractionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.TIME_LIMIT));
    }

    @Test
    void preservesInterruptionAndDoesNotReadTheSource() {
        var read = new AtomicBoolean();
        var input =
                new InputStream() {
                    @Override
                    public int read() {
                        read.set(true);
                        return -1;
                    }
                };
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new PdfTextExtractor(INPUT_LIMIT).extract(input))
                    .isInstanceOfSatisfying(
                            PdfExtractionException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(Reason.CANCELLED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(read).isFalse();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void leavesTheCallerOwnedStreamOpenOnSuccessAndFailure(boolean valid) throws Exception {
        var closed = new AtomicBoolean();
        var input =
                new ByteArrayInputStream(valid ? pdf("Resource ownership") : new byte[0]) {
                    @Override
                    public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                };
        try (input) {
            if (valid) assertThat(new PdfTextExtractor(INPUT_LIMIT).extract(input)).hasSize(1);
            else
                assertThatThrownBy(() -> new PdfTextExtractor(INPUT_LIMIT).extract(input))
                        .isInstanceOf(PdfExtractionException.class);
            assertThat(closed).isFalse();
        }
        assertThat(closed).isTrue();
    }

    @Test
    void translatesSourceIoFailureWithAPrivateCauseAndSafeMessage() {
        var cause = new IOException("sentinel-private-path");
        var input =
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw cause;
                    }
                };
        assertThatThrownBy(() -> new PdfTextExtractor(INPUT_LIMIT).extract(input))
                .isInstanceOfSatisfying(
                        PdfExtractionException.class,
                        failure ->
                                assertThat(failure.reason()).isEqualTo(Reason.SOURCE_UNAVAILABLE))
                .hasCause(cause)
                .hasMessageNotContaining("sentinel-private");
    }

    @Test
    void rejectsUnboundedOrNonPositiveExtractionPolicies() {
        assertThatThrownBy(() -> new PdfTextExtractor(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PdfTextExtractor(33L * 1024 * 1024))
                .isInstanceOf(IllegalArgumentException.class);
        for (var values : new int[][] {{0, 10, 10}, {1_001, 10, 10}, {1, 0, 10}, {1, 10, 0}}) {
            assertThatThrownBy(() -> extractor(values[0], values[1], values[2]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new PdfTextExtractor.Limits(INPUT_LIMIT, 1, 10, 10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PdfTextExtractor extractor(int pages, int characters, int operators) {
        return new PdfTextExtractor(limits(pages, characters, operators), CLOCK);
    }

    private static PdfTextExtractor.Limits limits(int pages, int characters, int operators) {
        return new PdfTextExtractor.Limits(
                INPUT_LIMIT, pages, characters, operators, Duration.ofSeconds(10));
    }

    private static void assertFailure(PdfTextExtractor extractor, byte[] bytes, Reason reason) {
        assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(bytes)))
                .isInstanceOfSatisfying(
                        PdfExtractionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason))
                .hasMessageNotContaining("sentinel-private");
    }

    private static byte[] pdf(String... pages) throws IOException {
        try (var document = new PDDocument()) {
            for (var text : pages) {
                var page = new PDPage();
                document.addPage(page);
                if (!text.isEmpty()) {
                    try (var content = new PDPageContentStream(document, page)) {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(30, 700);
                        content.showText(text);
                        content.endText();
                    }
                }
            }
            return save(document);
        }
    }

    private static byte[] save(PDDocument document) throws IOException {
        var bytes = new ByteArrayOutputStream();
        document.save(bytes);
        return bytes.toByteArray();
    }
}
