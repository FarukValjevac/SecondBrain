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

    DocumentIndexingReconciliation(
            JdbcIndexingRepository indexes,
            DocumentIndexingService indexing,
            EventPublisher events,
            ObjectProvider<DocumentPassageHandler> passages) {
        this.indexes = indexes;
        this.indexing = indexing;
        this.events = events;
        this.passages = passages;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    void reconcile() {
        try {
            for (var document : indexes.findUnenrolled(100)) {
                indexing.enroll(document, UUID.randomUUID());
            }
            if (passages.getIfAvailable() != null) {
                events.scheduleMissing(ReindexRequestedEvent.TYPE, DocumentPassageHandler.ID, 100);
            }
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(getClass())
                    .warn("Index enrollment will retry code=DATABASE_OR_HANDLER_UNAVAILABLE");
        }
    }
}
