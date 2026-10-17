package dev.secondbrain.documents;

import dev.secondbrain.events.EventEnvelope;

final class TextExtractedEvent {
    static final EventEnvelope.Type TYPE = new EventEnvelope.Type("TextExtracted", 1);

    private TextExtractedEvent() {}
}
