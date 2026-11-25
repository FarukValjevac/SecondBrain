package dev.secondbrain.documents;

import dev.secondbrain.events.EventPublisher;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.UUID;

/** Enrolls current extracted state, not a replay of retained historical extraction events. */
final class DocumentIndexingReconciliation {
    private final JdbcIndexingRepository indexes;
    private final DocumentIndexingService indexing;
    private final EventPublisher events;
    private final ObjectProvider<DocumentPassageHandler> passages;
    private final ObjectProvider<DocumentEmbeddingHandler> embeddings;
    private final ObjectProvider<DocumentIndexPublication> publication;
    private final boolean reprepare;

    DocumentIndexingReconciliation(
            JdbcIndexingRepository indexes,
            DocumentIndexingService indexing,
            EventPublisher events,
            ObjectProvider<DocumentPassageHandler> passages,
            ObjectProvider<DocumentEmbeddingHandler> embeddings,
            ObjectProvider<DocumentIndexPublication> publication) {
        this(indexes, indexing, events, passages, embeddings, publication, false);
    }

    DocumentIndexingReconciliation(
            JdbcIndexingRepository indexes,
            DocumentIndexingService indexing,
            EventPublisher events,
            ObjectProvider<DocumentPassageHandler> passages,
            ObjectProvider<DocumentEmbeddingHandler> embeddings,
            ObjectProvider<DocumentIndexPublication> publication,
            boolean reprepare) {
        this.indexes = indexes;
        this.indexing = indexing;
        this.events = events;
        this.passages = passages;
        this.embeddings = embeddings;
        this.publication = publication;
        this.reprepare = reprepare;
    }

    // Recovery for work that predates a handler or lost its delivery: soon after startup, then
    // rarely. New work is delivered by events, so this never needs to run every second.
    @Scheduled(
            initialDelayString = "${secondbrain.documents.reconcile-initial-delay:1s}",
            fixedDelayString = "${secondbrain.documents.reconcile-interval:30s}")
    void reconcile() {
        try {
            for (var document : indexes.findUnenrolled(100)) {
                indexing.enroll(document, UUID.randomUUID());
            }
            if (passages.getIfAvailable() != null) {
                events.scheduleMissing(ReindexRequestedEvent.TYPE, DocumentPassageHandler.ID, 100);
            }
            if (embeddings.getIfAvailable() != null) {
                events.scheduleMissing(
                        PassagesPreparedEvent.TYPE, DocumentEmbeddingHandler.ID, 100);
            }
            var recovery = publication.getIfAvailable();
            if (recovery != null) recovery.reconcile();
            // Opt-in: re-prepare a few documents per pass with the current passage splitting.
            // Each one is embedded again, which uses the provider's allowance; the old index
            // stays searchable until the new one is complete.
            if (reprepare && passages.getIfAvailable() != null)
                for (var document : indexes.findOutdatedPassages(5))
                    indexing.requestNext(document, UUID.randomUUID());
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(getClass())
                    .atWarn()
                    .addKeyValue("operation", "document_indexing_reconciliation")
                    .addKeyValue("code", "DATABASE_OR_HANDLER_UNAVAILABLE")
                    .log("Index enrollment will retry");
        }
    }
}
