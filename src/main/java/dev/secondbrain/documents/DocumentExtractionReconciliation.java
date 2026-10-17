package dev.secondbrain.documents;

import dev.secondbrain.events.EventPublisher;

import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

final class DocumentExtractionReconciliation {
    private final EventPublisher events;

    DocumentExtractionReconciliation(EventPublisher events) {
        this.events = events;
    }

    @Scheduled(fixedDelay = 1000)
    void reconcile() {
        try {
            events.scheduleMissing(DocumentAcceptedEvent.TYPE, DocumentExtractionHandler.ID, 100);
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(getClass())
                    .warn("Extraction scheduling will retry code=DATABASE_OR_HANDLER_UNAVAILABLE");
        }
    }
}
