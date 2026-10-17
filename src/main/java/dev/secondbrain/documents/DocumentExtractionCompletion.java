package dev.secondbrain.documents;

import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventPublisher;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Database-only effects; the durable dispatcher will own the enclosing lease-fenced transaction.
 */
final class DocumentExtractionCompletion {
    private final JdbcDocumentRepository documents;
    private final JdbcExtractedTextRepository texts;
    private final EventPublisher events;
    private final Clock clock;
    private final TransactionTemplate mandatory;

    DocumentExtractionCompletion(
            JdbcDocumentRepository documents,
            JdbcExtractedTextRepository texts,
            EventPublisher events,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.texts = Objects.requireNonNull(texts, "texts");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        mandatory = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        // Never commit effects independently of the delivery acknowledgment.
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    /** Returns false for a missing, superseded, or already completed document version. */
    boolean complete(ExtractedText text, EventEnvelope accepted) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(accepted, "accepted");
        if (!accepted.type().equals(DocumentAcceptedEvent.TYPE)
                || !accepted.documentId().equals(text.documentId())
                || accepted.processingVersion() != text.processingVersion()) {
            throw new IllegalArgumentException("Extracted text must match its acceptance event");
        }
        return Objects.requireNonNull(
                mandatory.execute(
                        status -> {
                            var completedAt = clock.instant();
                            // The conditional UPDATE locks the row until the enclosing transaction
                            // ends.
                            if (!documents.markExtracted(
                                    text.documentId(), text.processingVersion(), completedAt)) {
                                return false;
                            }
                            texts.insert(text);
                            events.publish(
                                    new EventEnvelope(
                                            UUID.randomUUID(),
                                            TextExtractedEvent.TYPE,
                                            text.documentId(),
                                            text.processingVersion(),
                                            completedAt,
                                            accepted.correlationId(),
                                            accepted.eventId(),
                                            accepted.traceParent(),
                                            Map.of()));
                            return true;
                        }));
    }

    boolean fail(EventEnvelope accepted, TextExtractionException.Reason reason) {
        Objects.requireNonNull(reason, "reason");
        if (!accepted.type().equals(DocumentAcceptedEvent.TYPE))
            throw new IllegalArgumentException("Expected document acceptance");
        return Objects.requireNonNull(
                mandatory.execute(
                        status ->
                                documents.markFailed(
                                        accepted.documentId(),
                                        accepted.processingVersion(),
                                        reason,
                                        clock.instant())));
    }
}
