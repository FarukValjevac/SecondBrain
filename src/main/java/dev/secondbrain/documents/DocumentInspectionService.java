package dev.secondbrain.documents;

import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventPublisher;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** Public document-reading use cases; persistence types stay inside the documents feature. */
public final class DocumentInspectionService {
    private static final int PAGE_SIZE = 50;
    private final JdbcDocumentRepository documents;
    private final JdbcExtractedTextRepository texts;
    private final EventPublisher events;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final TransactionTemplate sourceReads;

    DocumentInspectionService(
            JdbcDocumentRepository documents,
            JdbcExtractedTextRepository texts,
            EventPublisher events,
            PlatformTransactionManager manager,
            Clock clock) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.texts = Objects.requireNonNull(texts, "texts");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        transactions = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transactions.setTimeout(5);
        sourceReads = new TransactionTemplate(manager);
        sourceReads.setReadOnly(true);
        sourceReads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        sourceReads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        sourceReads.setTimeout(5);
    }

    public LibraryPage list(int page) {
        if (page < 0) throw new IllegalArgumentException("Page must not be negative");
        return Objects.requireNonNull(
                transactions.execute(
                        status -> {
                            // One extra row establishes whether another page exists without
                            // counting all documents.
                            var rows = documents.list(PAGE_SIZE + 1, (long) page * PAGE_SIZE);
                            var summaries = new ArrayList<DocumentSummary>();
                            for (var row : rows.subList(0, Math.min(PAGE_SIZE, rows.size()))) {
                                var state = row.state().displayedWith(row.deliveryState());
                                summaries.add(
                                        new DocumentSummary(
                                                row.documentId(),
                                                row.name(),
                                                state,
                                                row.createdAt()));
                            }
                            return new LibraryPage(summaries, page, rows.size() > PAGE_SIZE);
                        }));
    }

    /** Lightweight visible metadata for confirmation screens; never loads document text. */
    public Optional<DocumentSummary> summary(UUID id) {
        Objects.requireNonNull(id, "id");
        return Objects.requireNonNull(
                transactions.execute(status -> documents.findById(id).map(this::summarize)));
    }

    /** Bounded visible labels only; does not load extracted text or processing details. */
    public Map<UUID, String> names(Set<UUID> documentIds) {
        var ids = Set.copyOf(documentIds);
        if (ids.size() > 100)
            throw new IllegalArgumentException("Too many document names requested");
        if (ids.isEmpty()) return Map.of();
        return Map.copyOf(
                Objects.requireNonNull(transactions.execute(status -> documents.names(ids))));
    }

    /** Re-resolve stable passage identity against a fresh committed visibility snapshot. */
    public Optional<PassageSource> source(UUID documentId, UUID passageId) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(passageId, "passageId");
        return Objects.requireNonNull(
                sourceReads.execute(status -> documents.source(documentId, passageId)));
    }

    private DocumentSummary summarize(DocumentMetadata document) {
        var progress = documents.progress(document.documentId(), document.processingVersion());
        var state = document.extractionState().displayedWith(progress.deliveryState());
        return new DocumentSummary(
                document.documentId(), document.originalName(), state, document.createdAt());
    }

    public Optional<DocumentView> find(UUID id) {
        Objects.requireNonNull(id, "id");
        return Objects.requireNonNull(
                transactions.execute(status -> documents.findById(id).map(this::view)));
    }

    private DocumentView view(DocumentMetadata document) {
        var id = document.documentId();
        var progress = documents.progress(id, document.processingVersion());
        var state = document.extractionState().displayedWith(progress.deliveryState());
        boolean active =
                progress.deliveryState().equals("PENDING")
                        || progress.deliveryState().equals("RUNNING");
        var lines =
                state == ExtractionState.EXTRACTED
                        ? texts
                                .findByDocumentVersion(id, document.processingVersion())
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Completed extraction is missing"))
                                .lines()
                                .stream()
                                .map(
                                        line ->
                                                new Line(
                                                        line.number(),
                                                        line.text(),
                                                        line.pageNumber()))
                                .toList()
                        : List.<Line>of();
        String message = progress.failure().map(TextExtractionException.Reason::message).orElse("");
        if (state == ExtractionState.FAILED && message.isEmpty()) {
            message = "Processing could not be completed. Try again.";
        }
        if (active && !message.isEmpty()) message += " Retrying automatically.";
        return new DocumentView(
                id,
                document.originalName(),
                document.processingVersion(),
                state,
                message,
                state == ExtractionState.FAILED && progress.deliveryState().equals("FAILED"),
                lines);
    }

    public boolean retry(UUID id, UUID correlationId) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlationId, "correlationId");
        return Objects.requireNonNull(
                transactions.execute(
                        status -> {
                            var document = documents.findById(id);
                            if (document.isEmpty()) return false;
                            var version = document.orElseThrow().processingVersion();
                            var now = clock.instant();
                            if (!documents.retry(id, version, now)) return false;
                            events.publish(
                                    new EventEnvelope(
                                            UUID.randomUUID(),
                                            DocumentAcceptedEvent.TYPE,
                                            id,
                                            Math.addExact(version, 1),
                                            now,
                                            correlationId,
                                            null,
                                            null,
                                            Map.of()));
                            return true;
                        }));
    }

    /** Public source projection; independent of search ranking and provider responses. */
    public record PassageSource(
            UUID documentId,
            UUID passageId,
            long indexingVersion,
            String name,
            String text,
            SourceLocation location) {
        public PassageSource {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(passageId, "passageId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(location, "location");
            if (indexingVersion < 1
                    || text.isBlank()
                    || text.length() > 2400
                    || text.codePointCount(0, text.length()) > PassageSplitter.MAXIMUM_CODE_POINTS
                    || text.indexOf('\0') >= 0
                    || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(text))
                throw new IllegalArgumentException("Invalid passage source");
        }

        @Override
        public String toString() {
            return "PassageSource[indexingVersion=" + indexingVersion + "]";
        }
    }

    public record SourceLocation(
            int startLine, int startColumn, int endLine, int endColumn, OptionalInt pageNumber) {
        public SourceLocation {
            // Share document location invariants without exposing staging types.
            new PreparedPassages.Location(startLine, startColumn, endLine, endColumn, pageNumber);
        }
    }

    public record DocumentSummary(
            UUID documentId, String name, ExtractionState state, Instant createdAt) {
        public DocumentSummary {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record LibraryPage(List<DocumentSummary> documents, int page, boolean hasNext) {
        public LibraryPage {
            documents = List.copyOf(documents);
            if (page < 0) throw new IllegalArgumentException("Page must not be negative");
        }
    }

    public record Line(int number, String text, OptionalInt pageNumber) {
        public Line(int number, String text) {
            this(number, text, OptionalInt.empty());
        }

        public Line {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(pageNumber, "pageNumber");
            if (number < 1 || (pageNumber.isPresent() && pageNumber.getAsInt() < 1)) {
                throw new IllegalArgumentException("Source line and page numbers must be positive");
            }
        }
    }

    public record DocumentView(
            UUID documentId,
            String name,
            long processingVersion,
            ExtractionState state,
            String message,
            boolean retryAllowed,
            List<Line> lines) {
        public DocumentView {
            lines = List.copyOf(lines);
        }
    }
}
