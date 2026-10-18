package dev.secondbrain.documents;

import dev.secondbrain.events.DeliveryFailure;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;

import java.util.Objects;

/**
 * Bridges extraction versions to independent index generations in the acknowledgment transaction.
 */
final class DocumentIndexingHandler implements EventHandler {
    static final String ID = "documents.request-index.v1";
    private final DocumentIndexingService indexing;

    DocumentIndexingHandler(DocumentIndexingService indexing) {
        this.indexing = Objects.requireNonNull(indexing, "indexing");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public EventEnvelope.Type eventType() {
        return TextExtractedEvent.TYPE;
    }

    @Override
    public Effect prepare(EventEnvelope event) {
        if (!eventType().equals(event.type())) {
            throw new DeliveryFailure(new IllegalArgumentException("Unsupported extraction event"));
        }
        return () -> indexing.enroll(event).isPresent() ? Outcome.COMPLETED : Outcome.SKIPPED;
    }
}
