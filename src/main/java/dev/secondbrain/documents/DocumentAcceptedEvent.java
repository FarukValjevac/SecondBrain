package dev.secondbrain.documents;

import dev.secondbrain.events.EventEnvelope;

final class DocumentAcceptedEvent {
    static final EventEnvelope.Type TYPE = new EventEnvelope.Type("DocumentAccepted", 1);

    private DocumentAcceptedEvent() {}
}
