package dev.secondbrain.summaries;

import dev.secondbrain.events.DeliveryFailure;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;

import java.util.Objects;

/**
 * Summarizes a document after its index is published, or when the user asks again. Both events lead
 * to the same work against whatever generation is active when the delivery runs.
 */
final class SummaryHandler implements EventHandler {
    static final EventEnvelope.Type INDEX_PUBLISHED = new EventEnvelope.Type("IndexPublished", 1);
    static final EventEnvelope.Type REQUESTED = new EventEnvelope.Type("SummaryRequested", 1);
    private final String id;
    private final EventEnvelope.Type type;
    private final SummaryGenerator generator;

    private SummaryHandler(String id, EventEnvelope.Type type, SummaryGenerator generator) {
        this.id = id;
        this.type = type;
        this.generator = Objects.requireNonNull(generator, "generator");
    }

    static SummaryHandler afterPublication(SummaryGenerator generator) {
        return new SummaryHandler("summaries.index-published.v1", INDEX_PUBLISHED, generator);
    }

    static SummaryHandler onRequest(SummaryGenerator generator) {
        return new SummaryHandler("summaries.requested.v1", REQUESTED, generator);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public EventEnvelope.Type eventType() {
        return type;
    }

    @Override
    public Effect prepare(EventEnvelope event) throws InterruptedException {
        if (!type.equals(event.type()))
            throw new DeliveryFailure(new IllegalArgumentException("Unsupported summary event"));
        return generator.prepare(event.documentId());
    }
}
