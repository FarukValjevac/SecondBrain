package dev.secondbrain.documents;

import dev.secondbrain.events.DeliveryFailure;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;
import dev.secondbrain.events.EventPublisher;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.Objects;

final class DocumentDeletionHandler implements EventHandler {
    static final String ID = "documents.delete.v1";
    private final JdbcDocumentRepository documents;
    private final OriginalFileStore originals;
    private final EventPublisher events;
    private final TransactionTemplate mandatory;

    DocumentDeletionHandler(
            JdbcDocumentRepository documents,
            OriginalFileStore originals,
            EventPublisher events,
            PlatformTransactionManager manager) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.originals = Objects.requireNonNull(originals, "originals");
        this.events = Objects.requireNonNull(events, "events");
        mandatory = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public EventEnvelope.Type eventType() {
        return DocumentDeletionRequestedEvent.TYPE;
    }

    @Override
    public Effect prepare(EventEnvelope event) throws IOException, InterruptedException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Original deletion must run outside a database transaction");
        }
        if (!eventType().equals(event.type())) {
            throw new DeliveryFailure(new IllegalArgumentException("Unsupported deletion request"));
        }
        checkInterrupted();
        var storage = documents.deletedStorageId(event.documentId(), event.processingVersion());
        if (storage.isEmpty()) return () -> Outcome.SKIPPED;
        var storageId = storage.orElseThrow();
        // A tombstone cannot be undone, and storage IDs are never reused. Duplicate deletes are
        // safe.
        originals.delete(storageId);
        checkInterrupted();
        return () ->
                Objects.requireNonNull(
                        mandatory.execute(
                                status -> {
                                    if (documents
                                            .deletedStorageId(
                                                    event.documentId(), event.processingVersion())
                                            .filter(storageId::equals)
                                            .isEmpty()) return Outcome.SKIPPED;
                                    // Lock old delivery records before the document row, matching
                                    // extraction's lock order.
                                    events.discardSuperseded(
                                            event.documentId(), event.processingVersion());
                                    return documents.finishDeletion(
                                                    event.documentId(),
                                                    event.processingVersion(),
                                                    storageId)
                                            ? Outcome.COMPLETED
                                            : Outcome.SKIPPED;
                                }));
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("Document cleanup interrupted");
    }
}
