package dev.secondbrain.documents;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;

/** Renders the pages of one PDF to greyscale images for text recognition. Close after use. */
final class PdfPageImages implements AutoCloseable {
    /** Caps very large page boxes so one page can never need an enormous image. */
    private static final int MAXIMUM_SIDE_PIXELS = 6000;

    private final PDDocument document;
    private final PDFRenderer renderer;

    private PdfPageImages(PDDocument document) {
        this.document = document;
        this.renderer = new PDFRenderer(document);
    }

    static PdfPageImages open(byte[] pdf) throws IOException {
        Objects.requireNonNull(pdf, "pdf");
        var document = Loader.loadPDF(pdf);
        if (document.isEncrypted()) {
            document.close();
            throw new IOException("Encrypted PDF");
        }
        return new PdfPageImages(document);
    }

    int pageCount() {
        return document.getNumberOfPages();
    }

    BufferedImage render(int pageNumber, int dpi) throws IOException {
        if (pageNumber < 1 || pageNumber > pageCount())
            throw new IllegalArgumentException("Page number out of range");
        var box = document.getPage(pageNumber - 1).getMediaBox();
        double longestSideInches = Math.max(box.getWidth(), box.getHeight()) / 72.0;
        float scale =
                (float)
                        Math.min(
                                dpi / 72.0,
                                longestSideInches <= 0
                                        ? dpi / 72.0
                                        : MAXIMUM_SIDE_PIXELS / (longestSideInches * 72.0));
        return renderer.renderImage(pageNumber - 1, scale, ImageType.GRAY);
    }

    @Override
    public void close() throws IOException {
        document.close();
    }
}
