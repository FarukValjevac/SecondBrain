package dev.secondbrain.documents;

import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.events.DeliveryFailure;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;
import dev.secondbrain.events.EventPublisher;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Shared fenced publication effect and durable recovery intake for legacy completed staging. */
final class DocumentIndexPublication implements EventHandler {
    static final String ID = "documents.publish-index.v1";
    private final JdbcIndexingRepository indexes;
    private final EventPublisher events;
    private final EmbeddingModel model;
    private final Clock clock;
    private final TransactionTemplate isolated;

    DocumentIndexPublication(
            JdbcIndexingRepository indexes,
            EventPublisher events,
            EmbeddingModel model,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        this.events = Objects.requireNonNull(events, "events");
        this.model = Objects.requireNonNull(model, "model");
        this.clock = Objects.requireNonNull(clock, "clock");
        isolated = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        isolated.setTimeout(5);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public EventEnvelope.Type eventType() {
        return IndexPublicationRequestedEvent.TYPE;
    }

    @Override
    public Effect prepare(EventEnvelope event) {
        if (!eventType().equals(event.type()))
            throw new DeliveryFailure(
                    new IllegalArgumentException("Unsupported publication request"));
        var current =
                indexes.findEligibleDesired(event.documentId())
                        .filter(version -> version.number() == event.processingVersion());
        return current.isEmpty()
                ? () -> Outcome.SKIPPED
                : () -> publish(current.orElseThrow(), event);
    }

    /** Joins the dispatcher's effect/acknowledgment transaction; never publishes after commit. */
    Outcome publish(IndexingVersion version, EventEnvelope cause) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Index publication requires a caller transaction");
        if ((!PassagesPreparedEvent.TYPE.equals(cause.type()) && !eventType().equals(cause.type()))
                || !version.documentId().equals(cause.documentId())
                || version.number() != cause.processingVersion())
            throw new DeliveryFailure(
                    new IllegalArgumentException("Publication cause must match its generation"));
        if (eventType().equals(cause.type())) {
            var current = indexes.lockEligibleDesired(version.documentId(), version.number());
            if (current.filter(version::equals).isEmpty()
                    || !indexes.completedEmbeddingSource(version, cause.causationId()))
                return Outcome.SKIPPED;
        }
        var now = clock.instant();
        final JdbcIndexingRepository.Activation outcome;
        try {
            outcome = indexes.activate(version, model, now);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new DeliveryFailure(invalid);
        }
        return switch (outcome) {
            case INELIGIBLE -> Outcome.SKIPPED;
            case INCOMPLETE ->
                    throw new DeliveryFailure(
                            new IllegalStateException(
                                    "Index publication prerequisites are incomplete"));
            case ACTIVATED, ALREADY_ACTIVE -> {
                // Repair a selected generation lacking its fact; logical identity deduplicates
                // retries.
                events.publish(
                        new EventEnvelope(
                                UUID.randomUUID(),
                                IndexPublishedEvent.TYPE,
                                version.documentId(),
                                version.number(),
                                now,
                                cause.correlationId(),
                                cause.eventId(),
                                cause.traceParent(),
                                Map.of()));
                yield Outcome.COMPLETED;
            }
        };
    }

    void reconcile() {
        for (var candidate : indexes.findPublicationRecovery(model, 100)) {
            isolated.executeWithoutResult(
                    status -> {
                        var version = candidate.version();
                        var current =
                                indexes.lockEligibleDesired(version.documentId(), version.number());
                        if (current.filter(version::equals).isEmpty()
                                || !indexes.canRecoverPublication(version, model)) return;
                        events.publish(
                                new EventEnvelope(
                                        UUID.randomUUID(),
                                        IndexPublicationRequestedEvent.TYPE,
                                        version.documentId(),
                                        version.number(),
                                        clock.instant(),
                                        candidate.correlationId(),
                                        candidate.passagesEventId(),
                                        candidate.traceParent(),
                                        Map.of()));
                    });
        }
        // Retained recovery intents may have been created while their handler was paused.
        events.scheduleMissing(eventType(), ID, 100);
    }
}
