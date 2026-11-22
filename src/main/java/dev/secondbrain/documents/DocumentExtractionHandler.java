package dev.secondbrain.documents;

import dev.secondbrain.events.DeliveryFailure;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

final class DocumentExtractionHandler implements EventHandler {
    static final String ID = "documents.extract-text.v1";
    private final JdbcDocumentRepository documents;
    private final DocumentTextExtractionService extraction;
    private final DocumentExtractionCompletion completion;
    private final Optional<PdfOcr> ocr;
    private final Clock clock;

    DocumentExtractionHandler(
            JdbcDocumentRepository documents,
            DocumentTextExtractionService extraction,
            DocumentExtractionCompletion completion) {
        this(documents, extraction, completion, Optional.empty(), Clock.systemUTC());
    }

    DocumentExtractionHandler(
            JdbcDocumentRepository documents,
            DocumentTextExtractionService extraction,
            DocumentExtractionCompletion completion,
            Optional<PdfOcr> ocr,
            Clock clock) {
        this.documents = Objects.requireNonNull(documents);
        this.extraction = Objects.requireNonNull(extraction);
        this.completion = Objects.requireNonNull(completion);
        this.ocr = Objects.requireNonNull(ocr);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public EventEnvelope.Type eventType() {
        return DocumentAcceptedEvent.TYPE;
    }

    @Override
    public Effect prepare(EventEnvelope event) throws InterruptedException {
        return prepare(event, Optional.empty());
    }

    /** With checkpoints, scanned PDFs are recognised page by page and resume after retries. */
    @Override
    public Effect prepare(EventEnvelope event, Checkpoint checkpoint) throws InterruptedException {
        return prepare(event, Optional.of(Objects.requireNonNull(checkpoint, "checkpoint")));
    }

    private Effect prepare(EventEnvelope event, Optional<Checkpoint> checkpoint)
            throws InterruptedException {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Extraction must run outside a database transaction");
        if (!eventType().equals(event.type()))
            throw new DeliveryFailure(new IllegalArgumentException("Unsupported acceptance event"));
        var document = documents.findById(event.documentId());
        if (document.isEmpty()
                || document.orElseThrow().processingVersion() != event.processingVersion()
                || document.orElseThrow().extractionState() == ExtractionState.EXTRACTED)
            return () -> Outcome.SKIPPED;
        try {
            var text = extraction.extract(document.orElseThrow());
            return () -> completion.complete(text, event) ? Outcome.COMPLETED : Outcome.SKIPPED;
        } catch (TextExtractionException failure) {
            if (failure.reason() == TextExtractionException.Reason.PDF_NO_TEXT
                    && ocr.isPresent()
                    && checkpoint.isPresent())
                return recognise(document.orElseThrow(), event, ocr.get(), checkpoint.get());
            if (failure.reason() == TextExtractionException.Reason.CANCELLED) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("Text extraction interrupted");
            }
            throw new DeliveryFailure(
                    failure,
                    failure.reason() == TextExtractionException.Reason.SOURCE_UNAVAILABLE,
                    () ->
                            completion.fail(event, failure.reason())
                                    ? Outcome.COMPLETED
                                    : Outcome.SKIPPED);
        }
    }

    /**
     * Recognises the pages of a scanned PDF that are not saved yet, saving each page as durable
     * progress. A timeout or restart therefore continues where it stopped; a deleted or reprocessed
     * document stops at the next page.
     */
    private Effect recognise(
            DocumentMetadata document, EventEnvelope event, PdfOcr ocr, Checkpoint checkpoint)
            throws InterruptedException {
        var settings = ocr.settings();
        try (var images = ocr.open(document.storageId())) {
            int count = images.pageCount();
            if (count < 1 || count > settings.maximumPages())
                return fail(event, TextExtractionException.Reason.PDF_PAGE_LIMIT);
            var done = ocr.pages().recognised(document.documentId(), document.processingVersion());
            for (int page = 1; page <= count; page++) {
                if (done.containsKey(page)) continue;
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException("Text recognition interrupted");
                var text = clean(ocr.engine().recognise(images.render(page, settings.dpi())));
                int number = page;
                var saved =
                        checkpoint.save(
                                () -> {
                                    if (!current(document)) return Outcome.SKIPPED;
                                    ocr.pages()
                                            .save(
                                                    document.documentId(),
                                                    document.processingVersion(),
                                                    number,
                                                    count,
                                                    text,
                                                    clock.instant());
                                    return Outcome.COMPLETED;
                                });
                if (saved.isEmpty() || saved.orElseThrow() == Outcome.SKIPPED)
                    return () -> Outcome.SKIPPED;
                done.put(page, text);
            }
            var pages = new ArrayList<PdfTextExtractor.Page>();
            for (var entry : done.entrySet())
                pages.add(new PdfTextExtractor.Page(entry.getKey(), entry.getValue()));
            if (pages.stream().allMatch(page -> page.text().isBlank()))
                return fail(event, TextExtractionException.Reason.OCR_NO_TEXT);
            final ExtractedText text;
            try {
                text =
                        new ExtractedText(
                                document.documentId(),
                                document.processingVersion(),
                                clock.instant(),
                                DocumentTextExtractionService.linesFromPages(pages));
            } catch (TextExtractionException invalid) {
                return fail(event, invalid.reason());
            }
            return () -> {
                if (!completion.complete(text, event)) return Outcome.SKIPPED;
                ocr.pages().delete(document.documentId());
                return Outcome.COMPLETED;
            };
        } catch (OcrEngine.Unavailable missing) {
            return fail(event, TextExtractionException.Reason.OCR_UNAVAILABLE);
        } catch (OcrEngine.PageFailed retry) {
            // Retried with backoff; saved pages are kept, so only unfinished pages run again.
            throw new DeliveryFailure(retry, true, (java.time.Instant) null);
        } catch (TextExtractionException failure) {
            return fail(event, failure.reason());
        } catch (java.io.IOException unreadable) {
            return fail(event, TextExtractionException.Reason.PDF_UNREADABLE);
        }
    }

    private Effect fail(EventEnvelope event, TextExtractionException.Reason reason) {
        var failure = new TextExtractionException(reason);
        throw new DeliveryFailure(
                failure,
                false,
                () -> completion.fail(event, reason) ? Outcome.COMPLETED : Outcome.SKIPPED);
    }

    /** Still the same visible, unextracted version of the document. */
    private boolean current(DocumentMetadata expected) {
        return documents
                .findById(expected.documentId())
                .filter(found -> found.processingVersion() == expected.processingVersion())
                .filter(found -> found.extractionState() != ExtractionState.EXTRACTED)
                .isPresent();
    }

    /** Tesseract separates pages with form feeds and may emit control characters. */
    private static String clean(String text) {
        var cleaned = new StringBuilder(text.length());
        text.codePoints()
                .forEach(
                        point -> {
                            if (point == '\n' || point == '\t' || !Character.isISOControl(point))
                                cleaned.appendCodePoint(point);
                        });
        return cleaned.toString().strip();
    }
}
