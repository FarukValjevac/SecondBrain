package dev.secondbrain.documents;

import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventPublisher;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Hides a document and records its cleanup request in the same database transaction. */
public final class DocumentDeletionService {
    private final JdbcDocumentRepository documents;
    private final EventPublisher events;
    private final Clock clock;
    private final DocumentTelemetry telemetry;
    private final TransactionTemplate transactions;

    DocumentDeletionService(
            JdbcDocumentRepository documents,
            EventPublisher events,
            PlatformTransactionManager manager,
            Clock clock,
            DocumentTelemetry telemetry) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        transactions = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transactions.setTimeout(5);
    }

    /** Returns true for a new request; absent or already deleted documents are unchanged. */
    public boolean request(UUID documentId, UUID correlationId) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(correlationId, "correlationId");
        var operation = telemetry.deletion(correlationId);
        try {
            return Objects.requireNonNull(
                    transactions.execute(
                            status -> {
                                var now = clock.instant();
                                var version = documents.requestDeletion(documentId, now);
                                boolean requested = version.isPresent();
                                if (requested) {
                                    events.publish(
                                            new EventEnvelope(
                                                    UUID.randomUUID(),
                                                    DocumentDeletionRequestedEvent.TYPE,
                                                    documentId,
                                                    version.orElseThrow(),
                                                    now,
                                                    correlationId,
                                                    null,
                                                    null,
                                                    Map.of()));
                                }
                                // REQUIRED may join a caller transaction; report success only on
                                // its actual commit.
                                TransactionSynchronizationManager.registerSynchronization(
                                        new TransactionSynchronization() {
                                            @Override
                                            public void afterCommit() {
                                                operation.outcome(
                                                        requested
                                                                ? DocumentTelemetry.Outcome
                                                                        .REQUESTED
                                                                : DocumentTelemetry.Outcome
                                                                        .UNCHANGED);
                                            }

                                            @Override
                                            public void afterCompletion(int status) {
                                                operation.close();
                                            }
                                        });
                                return requested;
                            }));
        } catch (RuntimeException failure) {
            operation.close();
            throw failure;
        }
    }
}
