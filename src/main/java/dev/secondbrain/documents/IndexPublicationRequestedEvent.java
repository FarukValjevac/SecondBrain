package dev.secondbrain.documents;

import dev.secondbrain.events.EventEnvelope;

/** Recovery intent for completed embedding work; never replaces pending or failed deliveries. */
final class IndexPublicationRequestedEvent {
    static final EventEnvelope.Type TYPE = new EventEnvelope.Type("IndexPublicationRequested", 1);

    private IndexPublicationRequestedEvent() {}
}
