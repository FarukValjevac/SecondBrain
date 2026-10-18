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
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;

/** Allocates index generations and their durable requests in one short transaction. */
public final class DocumentIndexingService {
    private final JdbcIndexingRepository indexes;
    private final EventPublisher events;
    private final Clock clock;
    private final DocumentTelemetry telemetry;
    private final TransactionTemplate transactions;

    DocumentIndexingService(
            JdbcIndexingRepository indexes,
            EventPublisher events,
            PlatformTransactionManager manager,
            Clock clock,
            DocumentTelemetry telemetry) {
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        transactions = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transactions.setTimeout(5);
    }

    /** Explicit new generation; repeated calls intentionally request another index. */
    public Optional<Receipt> requestNext(UUID documentId, UUID correlationId) {
        return request(documentId, OptionalLong.empty(), correlationId);
    }

    /** Idempotent for a specified generation; obsolete or ineligible requests are unchanged. */
    public Optional<Receipt> request(UUID documentId, long version, UUID correlationId) {
        if (version < 1) throw new IllegalArgumentException("Index version must be positive");
        return request(documentId, OptionalLong.of(version), correlationId);
    }

    private Optional<Receipt> request(UUID documentId, OptionalLong version, UUID correlationId) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(correlationId, "correlationId");
        return execute(
                correlationId, () -> indexes.request(documentId, version, clock.instant()), null);
    }

    /** Idempotent enrollment for reconciliation, independent of historical event retention. */
    Optional<Receipt> enroll(UUID documentId, UUID correlationId) {
        Objects.requireNonNull(documentId, "documentId");
        return execute(
                correlationId,
                () -> indexes.enroll(documentId, OptionalLong.empty(), clock.instant()),
                null);
    }

    /** Called only inside TextExtracted's lease-fenced acknowledgment transaction. */
    Optional<Receipt> enroll(EventEnvelope extracted) {
        if (!TextExtractedEvent.TYPE.equals(extracted.type())) {
            throw new IllegalArgumentException("Expected text extraction event");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Extraction handoff requires a transaction");
        }
        return execute(
                extracted.correlationId(),
                () ->
                        indexes.enroll(
                                extracted.documentId(),
                                OptionalLong.of(extracted.processingVersion()),
                                clock.instant()),
                extracted);
    }

    private Optional<Receipt> execute(
            UUID correlationId, Supplier<Optional<IndexingVersion>> request, EventEnvelope cause) {
        Objects.requireNonNull(correlationId, "correlationId");
        var operation = telemetry.indexingRequest(correlationId);
        try {
            return Objects.requireNonNull(
                    transactions.execute(
                            status -> {
                                var now = clock.instant();
                                var requested = request.get();
                                var receipt =
                                        requested.map(
                                                index ->
                                                        new Receipt(
                                                                index.documentId(),
                                                                index.number(),
                                                                index.extractionVersion(),
                                                                events.publish(
                                                                        new EventEnvelope(
                                                                                UUID.randomUUID(),
                                                                                ReindexRequestedEvent
                                                                                        .TYPE,
                                                                                index.documentId(),
                                                                                index.number(),
                                                                                now,
                                                                                correlationId,
                                                                                cause == null
                                                                                        ? null
                                                                                        : cause
                                                                                                .eventId(),
                                                                                cause == null
                                                                                        ? null
                                                                                        : cause
                                                                                                .traceParent(),
                                                                                Map.of()))));
                                // REQUIRED may join a caller transaction: report success only after
                                // its commit.
                                TransactionSynchronizationManager.registerSynchronization(
                                        new TransactionSynchronization() {
                                            @Override
                                            public void afterCommit() {
                                                operation.outcome(
                                                        receipt.isPresent()
                                                                ? DocumentTelemetry.Outcome
                                                                        .REQUESTED
                                                                : DocumentTelemetry.Outcome
                                                                        .UNCHANGED);
                                            }

                                            @Override
                                            public void afterCompletion(int completionStatus) {
                                                operation.close();
                                            }
                                        });
                                return receipt;
                            }));
        } catch (RuntimeException failure) {
            operation.close();
            throw failure;
        }
    }

    public record Receipt(
            UUID documentId, long indexingVersion, long extractionVersion, UUID eventId) {
        public Receipt {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(eventId, "eventId");
            if (indexingVersion < 1 || extractionVersion < 1) {
                throw new IllegalArgumentException(
                        "Index and extraction versions must be positive");
            }
        }
    }
}
