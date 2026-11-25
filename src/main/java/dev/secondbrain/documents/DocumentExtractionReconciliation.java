package dev.secondbrain.documents;

import dev.secondbrain.events.EventPublisher;

import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

final class DocumentExtractionReconciliation {
    private final EventPublisher events;

    DocumentExtractionReconciliation(EventPublisher events) {
        this.events = events;
    }

    // Recovery for work that predates a handler or lost its delivery: soon after startup, then
    // rarely. New work is delivered by events, so this never needs to run every second.
    @Scheduled(
            initialDelayString = "${secondbrain.documents.reconcile-initial-delay:1s}",
            fixedDelayString = "${secondbrain.documents.reconcile-interval:30s}")
    void reconcile() {
        try {
            events.scheduleMissing(DocumentAcceptedEvent.TYPE, DocumentExtractionHandler.ID, 100);
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(getClass())
                    .atWarn()
                    .addKeyValue("operation", "document_extraction_reconciliation")
                    .addKeyValue("code", "DATABASE_OR_HANDLER_UNAVAILABLE")
                    .log("Extraction scheduling will retry");
        }
    }
}
