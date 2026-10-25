package dev.secondbrain.documents;

import dev.secondbrain.events.EventEnvelope;

final class IndexPublishedEvent {
    static final EventEnvelope.Type TYPE = new EventEnvelope.Type("IndexPublished", 1);

    private IndexPublishedEvent() {}
}
