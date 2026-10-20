package dev.secondbrain.documents;

import dev.secondbrain.events.DeliveryFailure;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;
import dev.secondbrain.events.EventPublisher;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads/splits before finalization, then stores the immutable snapshot and downstream fact
 * together.
 */
final class DocumentPassagePreparation implements DocumentPassageHandler.Stage {
    private final JdbcExtractedTextRepository texts;
    private final PassageSplitter splitter;
    private final JdbcPassageRepository passages;
    private final EventPublisher events;
    private final Clock clock;

    DocumentPassagePreparation(
            JdbcExtractedTextRepository texts,
            PassageSplitter splitter,
            JdbcPassageRepository passages,
            EventPublisher events,
            Clock clock) {
        this.texts = Objects.requireNonNull(texts, "texts");
        this.splitter = Objects.requireNonNull(splitter, "splitter");
        this.passages = Objects.requireNonNull(passages, "passages");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public EventHandler.Effect prepare(IndexingVersion version, EventEnvelope event)
            throws InterruptedException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Passage preparation must run outside a transaction");
        }
        if (!ReindexRequestedEvent.TYPE.equals(event.type())
                || !version.documentId().equals(event.documentId())
                || version.number() != event.processingVersion()) {
            throw new DeliveryFailure(
                    new IllegalArgumentException("Index request must match its generation"));
        }
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("Passage preparation interrupted");
        final PreparedPassages prepared;
        try {
            // The bounded source-read transaction closes before CPU-only splitting begins.
            var extracted =
                    texts.findByDocumentVersion(version.documentId(), version.extractionVersion());
            if (extracted.isEmpty()) return () -> EventHandler.Outcome.SKIPPED;
            prepared = splitter.split(version, extracted.orElseThrow());
        } catch (IllegalArgumentException | IllegalStateException invalidSource) {
            // Invalid stored content and policy limits cannot improve by repeating the same work.
            throw new DeliveryFailure(invalidSource);
        }
        return () -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Passage completion requires a caller transaction");
            }
            try {
                passages.insert(prepared);
            } catch (IllegalArgumentException | IllegalStateException conflict) {
                throw new DeliveryFailure(conflict);
            }
            // Also publish after an identical insert: legacy staged rows may have no downstream
            // fact.
            events.publish(
                    new EventEnvelope(
                            UUID.randomUUID(),
                            PassagesPreparedEvent.TYPE,
                            version.documentId(),
                            version.number(),
                            clock.instant(),
                            event.correlationId(),
                            event.eventId(),
                            event.traceParent(),
                            Map.of()));
            return EventHandler.Outcome.COMPLETED;
        };
    }
}
