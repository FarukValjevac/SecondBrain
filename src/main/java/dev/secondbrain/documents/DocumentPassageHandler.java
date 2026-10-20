package dev.secondbrain.documents;

import dev.secondbrain.events.DeliveryFailure;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** Guards preparation and commits the stage effect inside the dispatcher's fenced transaction. */
final class DocumentPassageHandler implements EventHandler {
    static final String ID = "documents.prepare-passages.v1";
    private final JdbcIndexingRepository indexes;
    private final Stage stage;
    private final TransactionTemplate mandatory;

    DocumentPassageHandler(
            JdbcIndexingRepository indexes, Stage stage, PlatformTransactionManager transactions) {
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        this.stage = Objects.requireNonNull(stage, "stage");
        mandatory = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public EventEnvelope.Type eventType() {
        return ReindexRequestedEvent.TYPE;
    }

    @Override
    public Effect prepare(EventEnvelope event) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Passage preparation must run outside a transaction");
        }
        if (!eventType().equals(event.type())) {
            throw new DeliveryFailure(new IllegalArgumentException("Unsupported indexing event"));
        }
        var current =
                indexes.findEligibleDesired(event.documentId())
                        .filter(index -> index.number() == event.processingVersion());
        if (current.isEmpty()) return () -> Outcome.SKIPPED;
        var index = current.orElseThrow();
        var effect = Objects.requireNonNull(stage.prepare(index, event), "stage effect");
        return () ->
                Objects.requireNonNull(
                        mandatory.execute(
                                status -> {
                                    // Recheck after preparation: reindexing, extraction, or
                                    // deletion may have won meanwhile.
                                    var eligible =
                                            indexes.lockEligibleDesired(
                                                    event.documentId(), event.processingVersion());
                                    return eligible.filter(index::equals).isPresent()
                                            ? effect.apply()
                                            : Outcome.SKIPPED;
                                }));
    }

    @FunctionalInterface
    interface Stage {
        Effect prepare(IndexingVersion version, EventEnvelope event) throws Exception;
    }
}
