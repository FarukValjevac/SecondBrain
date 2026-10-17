package dev.secondbrain.documents;

import dev.secondbrain.documents.TextExtractionException.Reason;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;

/** Prepares text from private storage; the delivery handler will own guarded persistence. */
final class DocumentTextExtractionService {
    private final OriginalFileStore originals;
    private final Utf8TextExtractor extractor;
    private final Clock clock;

    DocumentTextExtractionService(
            OriginalFileStore originals, Utf8TextExtractor extractor, Clock clock) {
        this.originals = Objects.requireNonNull(originals, "originals");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** File I/O only: call outside a database transaction, then fence any persisted effects. */
    ExtractedText extract(DocumentMetadata document) {
        Objects.requireNonNull(document, "document");
        try (var input = originals.open(document.storageId())) {
            var lines = extractor.extract(input);
            return new ExtractedText(
                    document.documentId(), document.processingVersion(), clock.instant(), lines);
        } catch (IOException unavailable) {
            throw new TextExtractionException(Reason.SOURCE_UNAVAILABLE, unavailable);
        }
    }
}
