package dev.secondbrain.summaries;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventPublisher;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reads stored summaries and queues explicit regeneration; it never calls the provider itself. */
public final class SummaryService {
    private final JdbcSummaryRepository summaries;
    private final DocumentInspectionService documents;
    private final EventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    SummaryService(
            JdbcSummaryRepository summaries,
            DocumentInspectionService documents,
            EventPublisher events,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.events = Objects.requireNonNull(events, "events");
        this.transactions = new TransactionTemplate(transactions);
        this.transactions.setTimeout(5);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The current summary, or empty when none exists or any of its sources is gone. */
    public Optional<DocumentSummary> find(UUID documentId) {
        var stored = summaries.find(documentId);
        if (stored.isEmpty()) return Optional.empty();
        var sources = new ArrayList<DocumentInspectionService.PassageSource>();
        for (var passage : stored.orElseThrow().passages()) {
            var source = documents.source(documentId, passage);
            if (source.isEmpty()) return Optional.empty();
            sources.add(source.orElseThrow());
        }
        return Optional.of(
                new DocumentSummary(
                        stored.orElseThrow().text(), stored.orElseThrow().generatedAt(), sources));
    }

    /** Queues a new summary for a visible document; the background worker does the request. */
    public void request(UUID documentId, UUID correlationId) {
        long number = summaries.nextRequest(documentId);
        transactions.executeWithoutResult(
                status ->
                        events.publish(
                                new EventEnvelope(
                                        UUID.randomUUID(),
                                        SummaryHandler.REQUESTED,
                                        documentId,
                                        number,
                                        clock.instant(),
                                        correlationId,
                                        null,
                                        null,
                                        Map.of())));
    }
}
