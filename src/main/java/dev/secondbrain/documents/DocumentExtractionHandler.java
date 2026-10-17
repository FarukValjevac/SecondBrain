package dev.secondbrain.documents;

import dev.secondbrain.events.DeliveryFailure;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

final class DocumentExtractionHandler implements EventHandler {
    static final String ID = "documents.extract-text.v1";
    private final JdbcDocumentRepository documents;
    private final DocumentTextExtractionService extraction;
    private final DocumentExtractionCompletion completion;

    DocumentExtractionHandler(
            JdbcDocumentRepository documents,
            DocumentTextExtractionService extraction,
            DocumentExtractionCompletion completion) {
        this.documents = Objects.requireNonNull(documents);
        this.extraction = Objects.requireNonNull(extraction);
        this.completion = Objects.requireNonNull(completion);
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
}
