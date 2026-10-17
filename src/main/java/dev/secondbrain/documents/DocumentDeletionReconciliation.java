package dev.secondbrain.documents;

import dev.secondbrain.events.EventPublisher;

import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

final class DocumentDeletionReconciliation {
    private final EventPublisher events;

    DocumentDeletionReconciliation(EventPublisher events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    @Scheduled(fixedDelay = 1000)
    void reconcile() {
        try {
            events.scheduleMissing(
                    DocumentDeletionRequestedEvent.TYPE, DocumentDeletionHandler.ID, 100);
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(getClass())
                    .atWarn()
                    .addKeyValue("operation", "document_deletion_scheduling")
                    .addKeyValue("code", "DATABASE_OR_HANDLER_UNAVAILABLE")
                    .log("Document cleanup scheduling will retry");
        }
    }
}
