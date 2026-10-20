package dev.secondbrain.documents;

import dev.secondbrain.events.EventEnvelope;

final class PassagesPreparedEvent {
    static final EventEnvelope.Type TYPE = new EventEnvelope.Type("PassagesPrepared", 1);

    private PassagesPreparedEvent() {}
}
