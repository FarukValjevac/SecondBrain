package dev.secondbrain.documents;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Everything text recognition needs: the stored original, the engine, page storage, settings. */
final class PdfOcr {
    private final OriginalFileStore originals;
    private final OcrEngine engine;
    private final JdbcOcrPageRepository pages;
    private final OcrProperties settings;

    PdfOcr(
            OriginalFileStore originals,
            OcrEngine engine,
            JdbcOcrPageRepository pages,
            OcrProperties settings) {
        this.originals = Objects.requireNonNull(originals, "originals");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Loads the stored original for rendering; bounded like text extraction. */
    PdfPageImages open(UUID storageId) throws IOException {
        try (var input = originals.open(storageId)) {
            var bytes = input.readNBytes((int) PdfTextExtractor.MAXIMUM_INPUT_BYTES + 1);
            if (bytes.length > PdfTextExtractor.MAXIMUM_INPUT_BYTES)
                throw new TextExtractionException(TextExtractionException.Reason.INPUT_TOO_LARGE);
            return PdfPageImages.open(bytes);
        }
    }

    OcrEngine engine() {
        return engine;
    }

    JdbcOcrPageRepository pages() {
        return pages;
    }

    OcrProperties settings() {
        return settings;
    }
}
