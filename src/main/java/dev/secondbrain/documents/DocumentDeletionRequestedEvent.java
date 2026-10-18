package dev.secondbrain.documents;

import dev.secondbrain.events.EventEnvelope;

final class DocumentDeletionRequestedEvent {
    static final EventEnvelope.Type TYPE = new EventEnvelope.Type("DocumentDeletionRequested", 1);

    private DocumentDeletionRequestedEvent() {}
}
