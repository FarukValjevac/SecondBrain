package dev.secondbrain.documents;

import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventPublisher;

import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Coordinates durable original storage with atomic metadata and event persistence. */
public final class DocumentAcceptanceService {
    private final DocumentUploadPolicy uploadPolicy;
    private final OriginalFileStore originalFileStore;
    private final JdbcDocumentRepository documentRepository;
    private final EventPublisher eventPublisher;
    private final TransactionOperations transactions;
    private final Clock clock;

    DocumentAcceptanceService(
            DocumentUploadPolicy uploadPolicy,
            OriginalFileStore originalFileStore,
            JdbcDocumentRepository documentRepository,
            EventPublisher eventPublisher,
            TransactionOperations transactions,
            Clock clock) {
        this.uploadPolicy = uploadPolicy;
        this.originalFileStore = originalFileStore;
        this.documentRepository = documentRepository;
        this.eventPublisher = eventPublisher;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Consumes the stream but leaves closing it to the caller. */
    public Receipt accept(
            String originalName, long declaredSize, InputStream input, UUID correlationId)
            throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(correlationId, "correlationId");
        var documentType = uploadPolicy.validate(originalName, declaredSize);
        try (var staged = originalFileStore.stage(input)) {
            var storedOriginal = staged.original();
            try {
                if (storedOriginal.sizeInBytes() != declaredSize) {
                    throw new IOException("Uploaded file size did not match its content");
                }
                var now = clock.instant();
                var document =
                        new DocumentMetadata(
                                UUID.randomUUID(),
                                originalName,
                                documentType,
                                storedOriginal.storageId(),
                                storedOriginal.sha256(),
                                storedOriginal.sizeInBytes(),
                                1,
                                ExtractionState.QUEUED,
                                now,
                                now);
                transactions.executeWithoutResult(
                        status -> {
                            documentRepository.insert(document);
                            eventPublisher.publish(
                                    new EventEnvelope(
                                            UUID.randomUUID(),
                                            DocumentAcceptedEvent.TYPE,
                                            document.documentId(),
                                            document.processingVersion(),
                                            now,
                                            correlationId,
                                            null,
                                            null,
                                            Map.of()));
                        });
                return new Receipt(document.documentId(), document.extractionState());
            } catch (IOException | RuntimeException failure) {
                try {
                    originalFileStore.delete(storedOriginal);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }
    }

    public record Receipt(UUID documentId, ExtractionState state) {
        public Receipt {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(state, "state");
        }
    }
}
