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
public final class PdfFixtures {
    private PdfFixtures() {}

    public static byte[] text(String... pages) throws IOException {
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

    /** A "scanned" PDF: each page is only an image of large printed text, with no text layer. */
    public static byte[] scanned(String... pages) throws IOException {
        try (var document = new PDDocument()) {
            for (var text : pages) {
                var page = new PDPage();
                document.addPage(page);
                var image =
                        new java.awt.image.BufferedImage(
                                1275, 1650, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
                var graphics = image.createGraphics();
                try {
                    graphics.setColor(java.awt.Color.WHITE);
                    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                    graphics.setColor(java.awt.Color.BLACK);
                    graphics.setRenderingHint(
                            java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    graphics.setFont(
                            new java.awt.Font(java.awt.Font.SERIF, java.awt.Font.PLAIN, 48));
                    int y = 200;
                    for (var line : text.split("\n")) {
                        graphics.drawString(line, 120, y);
                        y += 80;
                    }
                } finally {
                    graphics.dispose();
                }
                var picture =
                        org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(
                                document, image);
                try (var content = new PDPageContentStream(document, page)) {
                    var box = page.getMediaBox();
                    content.drawImage(picture, 0, 0, box.getWidth(), box.getHeight());
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
