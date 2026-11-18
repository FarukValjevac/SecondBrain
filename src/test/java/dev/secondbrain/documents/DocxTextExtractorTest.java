package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.secondbrain.documents.TextExtractionException.Reason;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class DocxTextExtractorTest {
    private final DocxTextExtractor extractor = new DocxTextExtractor(1024 * 1024);

    @Test
    void readsParagraphsTabsAndBreaksAsNumberedLines() throws IOException {
        var docx =
                DocxFixtures.document(
                        "<w:p><w:r><w:t>Java study</w:t></w:r><w:r><w:t xml:space=\"preserve\">"
                                + " notes</w:t></w:r></w:p>"
                                + "<w:p/>"
                                + "<w:p><w:r><w:t>Key</w:t><w:tab/><w:t>Value</w:t></w:r></w:p>"
                                + "<w:p><w:r><w:t>First</w:t><w:br/><w:t>Second &amp; 📚</w:t>"
                                + "</w:r></w:p>");
        assertThat(extractor.extract(new ByteArrayInputStream(docx)))
                .containsExactly(
                        new ExtractedText.Line(1, "Java study notes"),
                        new ExtractedText.Line(2, ""),
                        new ExtractedText.Line(3, "Key\tValue"),
                        new ExtractedText.Line(4, "First"),
                        new ExtractedText.Line(5, "Second & 📚"));
    }

    @Test
    void ignoresDeletedRevisionsAndFieldInstructions() throws IOException {
        var docx =
                DocxFixtures.document(
                        "<w:p><w:r><w:t>Kept</w:t></w:r>"
                                + "<w:del><w:r><w:delText>Removed</w:delText></w:r></w:del>"
                                + "<w:r><w:instrText>PAGE</w:instrText></w:r></w:p>");
        assertThat(extractor.extract(new ByteArrayInputStream(docx)))
                .containsExactly(new ExtractedText.Line(1, "Kept"));
    }

    @Test
    void rejectsLegacyEncryptedAndCorruptFilesWithAFixedReason() throws IOException {
        // An encrypted .docx and a legacy .doc are OLE compound files, not zip archives.
        var notZip = new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 1, 2, 3};
        var withoutBody = DocxFixtures.zip("word/other.xml", "<x/>");
        var brokenXml = DocxFixtures.zip("word/document.xml", "<w:document><w:body>");
        for (var bytes : new byte[][] {notZip, withoutBody, brokenXml})
            assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(bytes)))
                    .isInstanceOfSatisfying(
                            TextExtractionException.class,
                            failure ->
                                    assertThat(failure.reason()).isEqualTo(Reason.DOCX_UNREADABLE));
    }

    @Test
    void refusesDtdsSoEntityExpansionAndExternalFilesCannotBeUsed() throws IOException {
        var hostile =
                DocxFixtures.zip(
                        "word/document.xml",
                        "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM"
                                + " \"file:///etc/passwd\">]><w:document xmlns:w=\"http://schemas."
                                + "openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p>"
                                + "<w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>");
        assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(hostile)))
                .isInstanceOfSatisfying(
                        TextExtractionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.DOCX_UNREADABLE))
                .hasMessageNotContaining("root:");
    }

    @Test
    void stopsReadingABodyThatDecompressesBeyondItsBudget() throws IOException {
        // A few kilobytes of compressed whitespace, valid XML content, expand far beyond the
        // budget.
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(
                    ("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/"
                                    + "main\"><w:body>")
                            .getBytes(StandardCharsets.UTF_8));
            zip.write(" ".repeat(512 * 1024).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThat(bytes.size()).isLessThan(8 * 1024);
        assertThatThrownBy(
                        () ->
                                new DocxTextExtractor(64 * 1024)
                                        .extract(new ByteArrayInputStream(bytes.toByteArray())))
                .isInstanceOfSatisfying(
                        TextExtractionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.INPUT_TOO_LARGE));
    }

    @Test
    void aDocumentWithoutTextIsEmpty() throws IOException {
        assertThatThrownBy(
                        () ->
                                extractor.extract(
                                        new ByteArrayInputStream(DocxFixtures.document("<w:p/>"))))
                .isInstanceOfSatisfying(
                        TextExtractionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.EMPTY_TEXT));
    }

    @Test
    void leavesTheCallerOwnedStreamOpen() throws IOException {
        var closed = new boolean[1];
        var input =
                new ByteArrayInputStream(DocxFixtures.paragraphs("Text")) {
                    @Override
                    public void close() {
                        closed[0] = true;
                    }
                };
        extractor.extract(input);
        assertThat(closed[0]).isFalse();
    }
}
