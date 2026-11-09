package dev.secondbrain.documents;

import dev.secondbrain.events.EventHandler;
import dev.secondbrain.events.EventRetryService;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Current processing presentation and explicit recovery; no provider calls or new index versions.
 */
public final class DocumentProcessingService {
    private final JdbcProcessingRepository progress;
    private final JdbcDocumentRepository documents;
    private final EventRetryService retries;
    private final Supplier<List<EventHandler>> handlers;
    private final boolean polling;
    private final Clock clock;
    private final DocumentTelemetry telemetry;
    private final TransactionTemplate reads;

    DocumentProcessingService(
            JdbcProcessingRepository progress,
            JdbcDocumentRepository documents,
            EventRetryService retries,
            Supplier<List<EventHandler>> handlers,
            boolean polling,
            PlatformTransactionManager transactions,
            Clock clock,
            DocumentTelemetry telemetry) {
        this.progress = Objects.requireNonNull(progress);
        this.documents = Objects.requireNonNull(documents);
        this.retries = Objects.requireNonNull(retries);
        this.handlers = Objects.requireNonNull(handlers);
        this.polling = polling;
        this.clock = Objects.requireNonNull(clock);
        this.telemetry = Objects.requireNonNull(telemetry);
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        reads.setTimeout(5);
    }

    public Map<UUID, Progress> read(Set<UUID> documentIds) {
        var ids = Set.copyOf(documentIds);
        if (ids.size() > 50) throw new IllegalArgumentException("Too many processing rows");
        if (ids.isEmpty()) return Map.of();
        return Map.copyOf(
                Objects.requireNonNull(
                        reads.execute(
                                status ->
                                        progress.read(ids).stream()
                                                .collect(
                                                        Collectors.toMap(
                                                                JdbcProcessingRepository.Row
                                                                        ::documentId,
                                                                this::present)))));
    }

    public Cleanup cleanup() {
        var backlog =
                Objects.requireNonNull(
                        reads.execute(status -> documents.cleanupBacklog(clock.instant())));
        return new Cleanup(backlog.pending(), backlog.failed());
    }

    public boolean retry(UUID documentId, UUID deliveryId, int cycle, UUID correlationId) {
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(deliveryId);
        Objects.requireNonNull(correlationId);
        if (cycle < 0) throw new IllegalArgumentException("Invalid retry cycle");
        try (var operation = telemetry.processingRetry(correlationId)) {
            boolean requested =
                    polling
                            && retries.retry(
                                    deliveryId,
                                    cycle,
                                    correlationId,
                                    (handler, event) -> {
                                        if (!documentId.equals(event.documentId())
                                                || !progress.lockVisible(documentId)) return false;
                                        var current = progress.read(Set.of(documentId));
                                        if (current.isEmpty()) return false;
                                        var row = current.getFirst();
                                        if (!deliveryId.equals(row.deliveryId())
                                                || !handler.equals(row.handler())
                                                || present(row).retry().isEmpty()) return false;
                                        // Clear only the obsolete extraction failure, within the
                                        // same transaction as requeue.
                                        if (row.extraction() != ExtractionState.EXTRACTED)
                                            progress.clearExtractionFailure(
                                                    documentId, clock.instant());
                                        return true;
                                    });
            operation.outcome(
                    requested
                            ? DocumentTelemetry.Outcome.REQUESTED
                            : DocumentTelemetry.Outcome.UNCHANGED);
            return requested;
        }
    }

    private Progress present(JdbcProcessingRepository.Row row) {
        boolean extracted = row.extraction() == ExtractionState.EXTRACTED;
        var stage = stage(row);
        if (stage == Stage.COMPLETE)
            return new Progress(
                    stage,
                    State.READY,
                    "Ready for search and questions.",
                    row.lines(),
                    row.passages(),
                    row.vectors(),
                    Optional.empty(),
                    Optional.empty());
        var needed =
                switch (stage) {
                    case EXTRACTION -> DocumentExtractionHandler.ID;
                    case PASSAGES ->
                            row.desired() == null
                                    ? DocumentIndexingHandler.ID
                                    : DocumentPassageHandler.ID;
                    case EMBEDDINGS -> DocumentEmbeddingHandler.ID;
                    case PUBLICATION -> DocumentIndexPublication.ID;
                    case COMPLETE ->
                            throw new IllegalStateException(
                                    "Complete progress requires no handler");
                };
        boolean configured =
                handlers.get().stream()
                        .anyMatch(
                                h ->
                                        h.id().equals(needed)
                                                && (row.eventType() == null
                                                        || (h.eventType()
                                                                        .name()
                                                                        .equals(row.eventType())
                                                                && h.eventType().schemaVersion()
                                                                        == row.schemaVersion())));
        boolean failed = "FAILED".equals(row.state());
        var retry =
                failed
                                && polling
                                && configured
                                && row.attempts() < Integer.MAX_VALUE
                                && row.cycle() < Integer.MAX_VALUE
                        ? Optional.of(new Retry(row.deliveryId(), row.cycle()))
                        : Optional.<Retry>empty();
        String message;
        State state;
        if (failed) {
            state = State.FAILED;
            message =
                    row.extractionFailure()
                            .map(TextExtractionException.Reason::message)
                            .orElse(
                                    "This processing step could not be completed. Check configuration or availability, then retry.");
            if (!configured || !polling)
                message += " Resume processing in the application configuration before retrying.";
        } else if (!configured || !polling) {
            state = extracted ? State.TEXT_AVAILABLE : State.PAUSED;
            message =
                    extracted
                            ? "Text is available. Search preparation is paused; enable processing to continue."
                            : "Processing is paused. Enable processing and refresh this page.";
        } else if ("RUNNING".equals(row.state())) {
            state = State.PROCESSING;
            message = "Processing this step. Refresh to see saved progress.";
        } else {
            state = State.QUEUED;
            message =
                    row.cycleAttempts() > 0
                            ? "Waiting for an automatic retry. Saved progress is retained."
                            : "Waiting for processing. Refresh to see saved progress.";
        }
        var eligibleAt =
                "PENDING".equals(row.state())
                                && row.eligibleAt()
                                        .filter(t -> t.isAfter(clock.instant()))
                                        .isPresent()
                        ? row.eligibleAt()
                        : Optional.<Instant>empty();
        return new Progress(
                stage,
                state,
                message,
                row.lines(),
                row.passages(),
                row.vectors(),
                retry,
                eligibleAt);
    }

    private static Stage stage(JdbcProcessingRepository.Row row) {
        if (row.extraction() != ExtractionState.EXTRACTED) return Stage.EXTRACTION;
        if (row.desired() == null) return Stage.PASSAGES;
        if (row.desired().equals(row.active())) return Stage.COMPLETE;
        if ("IndexPublicationRequested".equals(row.eventType())) return Stage.PUBLICATION;
        return row.passages() > 0 ? Stage.EMBEDDINGS : Stage.PASSAGES;
    }

    public enum Stage {
        EXTRACTION("Extracting text"),
        PASSAGES("Preparing passages"),
        EMBEDDINGS("Preparing search"),
        PUBLICATION("Making search available"),
        COMPLETE("Search available");
        private final String label;

        Stage(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public enum State {
        QUEUED("Queued"),
        PROCESSING("Processing"),
        READY("Ready"),
        FAILED("Failed"),
        PAUSED("Paused"),
        TEXT_AVAILABLE("Text available");
        private final String label;

        State(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public record Progress(
            Stage stage,
            State state,
            String message,
            long lines,
            long passages,
            long vectors,
            Optional<Retry> retry,
            Optional<Instant> eligibleAt) {
        public Progress {
            Objects.requireNonNull(stage);
            Objects.requireNonNull(state);
            Objects.requireNonNull(message);
            Objects.requireNonNull(retry);
            Objects.requireNonNull(eligibleAt);
            if (lines < 0 || passages < 0 || vectors < 0)
                throw new IllegalArgumentException("Invalid processing counts");
        }
    }

    public record Retry(UUID deliveryId, int cycle) {
        public Retry {
            Objects.requireNonNull(deliveryId);
            if (cycle < 0) throw new IllegalArgumentException("Invalid retry cycle");
        }

        @Override
        public String toString() {
            return "Retry[redacted]";
        }
    }

    public record Cleanup(long pending, long failed) {
        public Cleanup {
            if (pending < 0 || failed < 0)
                throw new IllegalArgumentException("Invalid cleanup counts");
        }
    }
}
