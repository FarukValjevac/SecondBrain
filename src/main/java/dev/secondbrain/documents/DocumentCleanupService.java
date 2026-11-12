package dev.secondbrain.documents;

import dev.secondbrain.events.EventHandler;
import dev.secondbrain.events.EventRetryService;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Diagnostics and explicit recovery for hidden documents, using the original durable delivery. */
public final class DocumentCleanupService {
    private final JdbcDocumentRepository documents;
    private final EventRetryService retries;
    private final Supplier<List<EventHandler>> handlers;
    private final boolean polling;
    private final Clock clock;
    private final DocumentTelemetry telemetry;
    private final TransactionTemplate reads;

    DocumentCleanupService(
            JdbcDocumentRepository documents,
            EventRetryService retries,
            Supplier<List<EventHandler>> handlers,
            boolean polling,
            PlatformTransactionManager transactions,
            Clock clock,
            DocumentTelemetry telemetry) {
        this.documents = Objects.requireNonNull(documents);
        this.retries = Objects.requireNonNull(retries);
        this.handlers = Objects.requireNonNull(handlers);
        this.polling = polling;
        this.clock = Objects.requireNonNull(clock);
        this.telemetry = Objects.requireNonNull(telemetry);
        reads = new TransactionTemplate(Objects.requireNonNull(transactions));
        reads.setReadOnly(true);
        reads.setTimeout(5);
    }

    public Page read(int page) {
        if (page < 0) throw new IllegalArgumentException("Invalid cleanup page");
        boolean enabled = enabled();
        var now = clock.instant();
        var rows = Objects.requireNonNull(reads.execute(status -> documents.cleanupRequests(page)));
        return new Page(
                rows.stream().limit(50).map(row -> present(row, now, enabled)).toList(),
                page,
                rows.size() > 50,
                enabled);
    }

    private Request present(JdbcDocumentRepository.CleanupRow row, Instant now, boolean enabled) {
        State state;
        if ("FAILED".equals(row.state())) state = State.FAILED;
        else if (!enabled) state = State.PAUSED;
        else if ("RUNNING".equals(row.state())) state = State.RUNNING;
        else state = State.PENDING;
        boolean retryable =
                enabled
                        && state == State.FAILED
                        && row.schemaVersion()
                                == DocumentDeletionRequestedEvent.TYPE.schemaVersion()
                        && row.attempts() < Integer.MAX_VALUE
                        && row.cycle() < Integer.MAX_VALUE;
        return new Request(
                row.documentId(),
                row.requestedAt(),
                Math.max(0, Duration.between(row.requestedAt(), now).getSeconds()),
                state,
                row.attempts(),
                retryable
                        ? Optional.of(
                                new DocumentProcessingService.Retry(row.deliveryId(), row.cycle()))
                        : Optional.empty());
    }

    public boolean retry(UUID documentId, UUID deliveryId, int cycle, UUID correlationId) {
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(deliveryId);
        Objects.requireNonNull(correlationId);
        if (cycle < 0) throw new IllegalArgumentException("Invalid retry cycle");
        try (var operation = telemetry.cleanupRetry(correlationId)) {
            boolean requested =
                    enabled()
                            && retries.retry(
                                    deliveryId,
                                    cycle,
                                    correlationId,
                                    (handler, event) ->
                                            DocumentDeletionHandler.ID.equals(handler)
                                                    && DocumentDeletionRequestedEvent.TYPE.equals(
                                                            event.type())
                                                    && documentId.equals(event.documentId())
                                                    && documents.lockDeletion(
                                                            documentId, event.processingVersion()));
            operation.outcome(
                    requested
                            ? DocumentTelemetry.Outcome.REQUESTED
                            : DocumentTelemetry.Outcome.UNCHANGED);
            return requested;
        }
    }

    private boolean enabled() {
        return polling
                && handlers.get().stream()
                        .anyMatch(
                                handler ->
                                        DocumentDeletionHandler.ID.equals(handler.id())
                                                && DocumentDeletionRequestedEvent.TYPE.equals(
                                                        handler.eventType()));
    }

    public enum State {
        PENDING("Awaiting cleanup"),
        RUNNING("Cleaning up"),
        FAILED("Needs attention"),
        PAUSED("Paused");
        private final String label;

        State(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public record Request(
            UUID documentId,
            Instant requestedAt,
            long ageSeconds,
            State state,
            int attempts,
            Optional<DocumentProcessingService.Retry> retry) {
        public Request {
            Objects.requireNonNull(documentId);
            Objects.requireNonNull(requestedAt);
            Objects.requireNonNull(state);
            Objects.requireNonNull(retry);
            if (ageSeconds < 0 || attempts < 0)
                throw new IllegalArgumentException("Invalid cleanup counts");
        }

        @Override
        public String toString() {
            return "CleanupRequest[redacted]";
        }
    }

    public record Page(List<Request> requests, int page, boolean hasNext, boolean enabled) {
        public Page {
            requests = List.copyOf(requests);
            if (page < 0 || requests.size() > 50)
                throw new IllegalArgumentException("Invalid cleanup page");
        }
    }
}
