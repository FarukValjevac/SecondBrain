package dev.secondbrain.documents;

import dev.secondbrain.events.EventEnvelope;

/** processingVersion is the independent index generation, not the extraction version. */
final class ReindexRequestedEvent {
    static final EventEnvelope.Type TYPE = new EventEnvelope.Type("ReindexRequested", 1);

    private ReindexRequestedEvent() {}
}
