package dev.secondbrain.documents;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Tiny generated PDFs shared by parser, service, and durable-worker checks. */
final class PdfFixtures {
    private PdfFixtures() {}

    static byte[] text(String... pages) throws IOException {
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

    static byte[] save(PDDocument document) throws IOException {
        var bytes = new ByteArrayOutputStream();
        document.save(bytes);
        return bytes.toByteArray();
    }

    static byte[] encrypted() throws IOException {
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            var protection =
                    new StandardProtectionPolicy("owner", "password", new AccessPermission());
            protection.setEncryptionKeyLength(128);
            document.protect(protection);
            return save(document);
        }
    }
}
