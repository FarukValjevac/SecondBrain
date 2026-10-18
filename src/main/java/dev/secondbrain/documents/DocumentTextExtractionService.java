package dev.secondbrain.documents;

import dev.secondbrain.documents.TextExtractionException.Reason;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Prepares text from private storage; the delivery handler will own guarded persistence. */
final class DocumentTextExtractionService {
    private static final int MAXIMUM_PDF_LINES = 100_000;
    private final OriginalFileStore originals;
    private final Utf8TextExtractor extractor;
    private final PdfTextExtractor pdfExtractor;
    private final Clock clock;

    DocumentTextExtractionService(
            OriginalFileStore originals,
            Utf8TextExtractor extractor,
            PdfTextExtractor pdfExtractor,
            Clock clock) {
        this.originals = Objects.requireNonNull(originals, "originals");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.pdfExtractor = Objects.requireNonNull(pdfExtractor, "pdfExtractor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** File I/O only: call outside a database transaction, then fence any persisted effects. */
    ExtractedText extract(DocumentMetadata document) {
        Objects.requireNonNull(document, "document");
        try (var input = originals.open(document.storageId())) {
            var lines =
                    switch (document.documentType()) {
                        case TXT, MARKDOWN -> extractor.extract(input);
                        case PDF -> extractPdfLines(input);
                    };
            return new ExtractedText(
                    document.documentId(), document.processingVersion(), clock.instant(), lines);
        } catch (IOException unavailable) {
            throw new TextExtractionException(Reason.SOURCE_UNAVAILABLE, unavailable);
        } catch (PdfExtractionException failure) {
            throw new TextExtractionException(pdfFailureReason(failure.reason()), failure);
        }
    }

    private List<ExtractedText.Line> extractPdfLines(InputStream input) {
        var lines = new ArrayList<ExtractedText.Line>();
        for (var page : pdfExtractor.extract(input)) {
            if (Thread.currentThread().isInterrupted()) {
                throw new TextExtractionException(Reason.CANCELLED);
            }
            var remaining = MAXIMUM_PDF_LINES - lines.size();
            // Bound split allocation, retain trailing empty lines, and detect one overflow line.
            var pageLines = page.text().split("\\r\\n|\\r|\\n", remaining + 1);
            if (pageLines.length > remaining) {
                throw new TextExtractionException(Reason.TOO_MANY_LINES);
            }
            for (var content : pageLines) {
                if (content.indexOf('\0') >= 0) {
                    throw new TextExtractionException(Reason.INVALID_CONTENT);
                }
                lines.add(
                        new ExtractedText.Line(
                                lines.size() + 1, content, OptionalInt.of(page.number())));
            }
        }
        return lines;
    }

    private static Reason pdfFailureReason(PdfExtractionException.Reason reason) {
        return switch (reason) {
            case SOURCE_UNAVAILABLE -> Reason.SOURCE_UNAVAILABLE;
            case UNREADABLE -> Reason.PDF_UNREADABLE;
            case ENCRYPTED -> Reason.PDF_ENCRYPTED;
            case NO_TEXT -> Reason.PDF_NO_TEXT;
            case INPUT_TOO_LARGE -> Reason.INPUT_TOO_LARGE;
            case PAGE_LIMIT -> Reason.PDF_PAGE_LIMIT;
            case TEXT_LIMIT -> Reason.PDF_TEXT_LIMIT;
            case WORK_LIMIT -> Reason.PDF_WORK_LIMIT;
            case TIME_LIMIT -> Reason.PDF_TIME_LIMIT;
            case CANCELLED -> Reason.CANCELLED;
        };
    }
}
