package dev.secondbrain.documents;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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

    /** Enough for every citation of a full saved chat (500 turns of five sources) in one read. */
    public static final int MAXIMUM_SOURCES = 2500;

    private final JdbcDocumentRepository documents;
    private final JdbcExtractedTextRepository texts;
    private final TransactionTemplate transactions;
    private final TransactionTemplate sourceReads;

    DocumentInspectionService(
            JdbcDocumentRepository documents,
            JdbcExtractedTextRepository texts,
            PlatformTransactionManager manager) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.texts = Objects.requireNonNull(texts, "texts");
        transactions = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transactions.setTimeout(5);
        sourceReads = new TransactionTemplate(manager);
        sourceReads.setReadOnly(true);
        sourceReads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        sourceReads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        sourceReads.setTimeout(5);
    }

    /** Every visible document, newest first; used by document pickers. */
    public LibraryPage list(int page) {
        return page(page, Optional.empty(), false);
    }

    /**
     * Documents filed directly in one folder (not its subfolders), or unfiled documents when the
     * folder is empty; used by the library's folder view.
     */
    public LibraryPage listFolder(int page, Optional<UUID> folder) {
        Objects.requireNonNull(folder, "folder");
        return page(page, folder, true);
    }

    private LibraryPage page(int page, Optional<UUID> folder, boolean byFolder) {
        if (page < 0) throw new IllegalArgumentException("Page must not be negative");
        return Objects.requireNonNull(
                transactions.execute(
                        status -> {
                            // One extra row establishes whether another page exists without
                            // counting all documents.
                            var rows =
                                    byFolder
                                            ? documents.listFolder(
                                                    PAGE_SIZE + 1, (long) page * PAGE_SIZE, folder)
                                            : documents.list(
                                                    PAGE_SIZE + 1, (long) page * PAGE_SIZE);
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

    /** How far text recognition of a scanned PDF has come; empty when none is running. */
    public Optional<OcrProgress> ocrProgress(UUID id) {
        Objects.requireNonNull(id, "id");
        return Objects.requireNonNull(
                transactions.execute(
                        status ->
                                documents
                                        .ocrProgress(id)
                                        .map(counts -> new OcrProgress(counts[0], counts[1]))));
    }

    public record OcrProgress(int recognised, int total) {}

    /**
     * Earlier visible copies of exactly the same file, by content fingerprint (at most five). Used
     * only to warn; uploads are never blocked.
     */
    public List<Duplicate> duplicates(UUID id) {
        Objects.requireNonNull(id, "id");
        return Objects.requireNonNull(transactions.execute(status -> documents.duplicatesOf(id)));
    }

    /** {@code folderId} is null for an unfiled document. Names are private: never log them. */
    public record Duplicate(UUID documentId, String name, UUID folderId) {
        public Duplicate {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String toString() {
            return "Duplicate[documentId=" + documentId + "]";
        }
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

    /** Passage UUID -> owner UUID; all returned sources share one fresh committed SQL snapshot. */
    public Map<UUID, PassageSource> sources(Map<UUID, UUID> passageOwners) {
        Objects.requireNonNull(passageOwners, "passageOwners");
        if (passageOwners.size() > MAXIMUM_SOURCES)
            throw new IllegalArgumentException("Too many passage sources requested");
        var owners = Map.copyOf(passageOwners);
        if (owners.isEmpty()) return Map.of();
        return Map.copyOf(
                Objects.requireNonNull(sourceReads.execute(status -> documents.sources(owners))));
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
                transactions.execute(
                        status -> documents.findById(id).map(found -> view(found, true))));
    }

    /** PDF pages shown together in the reading and lines views. */
    public static final int PAGES_PER_SECTION = 20;

    /** Lines shown together for documents without pages. */
    public static final int LINES_PER_SECTION = 1000;

    /**
     * One section of a document's text: about {@value #PAGES_PER_SECTION} PDF pages, or {@value
     * #LINES_PER_SECTION} lines without pages. {@code part} is chosen directly, or as the section
     * holding {@code line} (for a source link or highlight); otherwise the first. Long books are
     * therefore never read or rendered whole.
     */
    public Optional<SectionView> findSection(UUID id, OptionalInt part, OptionalInt line) {
        Objects.requireNonNull(id, "id");
        return Objects.requireNonNull(
                transactions.execute(
                        status -> documents.findById(id).map(found -> section(found, part, line))));
    }

    private SectionView section(DocumentMetadata document, OptionalInt part, OptionalInt line) {
        var empty = view(document, false);
        if (empty.state() != ExtractionState.EXTRACTED)
            return new SectionView(empty, new Section(1, 1, List.of()));
        var id = document.documentId();
        long version = document.processingVersion();
        var extent = texts.extent(id, version);
        boolean paged = extent.lastPage().isPresent();
        int size = paged ? PAGES_PER_SECTION : LINES_PER_SECTION;
        int total = paged ? extent.lastPage().getAsInt() : extent.lines();
        int parts = Math.max(1, (total + size - 1) / size);
        int chosen = 1;
        if (part.isPresent()) chosen = part.getAsInt();
        else if (line.isPresent()) {
            var unit =
                    paged
                            ? texts.pageOfLine(id, version, line.getAsInt())
                            : OptionalInt.of(line.getAsInt());
            if (unit.isPresent()) chosen = (unit.getAsInt() - 1) / size + 1;
        }
        chosen = Math.min(Math.max(chosen, 1), parts);
        int first = (chosen - 1) * size + 1;
        int last = Math.min(chosen * size, total);
        var stored =
                paged
                        ? texts.linesOnPages(id, version, first, last)
                        : texts.linesBetween(id, version, first, last);
        var lines =
                stored.stream()
                        .map(found -> new Line(found.number(), found.text(), found.pageNumber()))
                        .toList();
        var labels = new ArrayList<String>();
        for (int index = 1; index <= parts; index++) {
            int from = (index - 1) * size + 1;
            int to = Math.min(index * size, total);
            labels.add((paged ? "Pages " : "Lines ") + (from == to ? from : from + "–" + to));
        }
        return new SectionView(
                new DocumentView(
                        empty.documentId(),
                        empty.name(),
                        empty.processingVersion(),
                        empty.state(),
                        empty.message(),
                        lines),
                new Section(chosen, parts, labels));
    }

    /** Which section is shown, how many there are, and their labels ("Pages 21–40"). */
    public record Section(int part, int parts, List<String> labels) {
        public Section {
            labels = List.copyOf(labels);
        }

        public boolean isSplit() {
            return parts > 1;
        }

        public String getLabel() {
            return labels.isEmpty() ? "" : labels.get(part - 1);
        }
    }

    public record SectionView(DocumentView document, Section section) {}

    /** The same view without its text lines, for pages that do not show the text. */
    public Optional<DocumentView> findWithoutText(UUID id) {
        Objects.requireNonNull(id, "id");
        return Objects.requireNonNull(
                transactions.execute(
                        status -> documents.findById(id).map(found -> view(found, false))));
    }

    /**
     * Lines {@code from..to} of a visible document's current extracted text, read on their own
     * rather than with the whole document. Empty when the document is not visible or not extracted.
     */
    public Optional<LineRange> lines(UUID id, int from, int to) {
        Objects.requireNonNull(id, "id");
        if (from < 1 || to < from || to - from > 1000)
            throw new IllegalArgumentException("Invalid line range");
        return Objects.requireNonNull(
                transactions.execute(
                        status ->
                                documents
                                        .findById(id)
                                        .filter(
                                                found ->
                                                        found.extractionState()
                                                                == ExtractionState.EXTRACTED)
                                        .map(
                                                found ->
                                                        new LineRange(
                                                                found.processingVersion(),
                                                                texts
                                                                        .linesBetween(
                                                                                id,
                                                                                found
                                                                                        .processingVersion(),
                                                                                from,
                                                                                to)
                                                                        .stream()
                                                                        .map(
                                                                                line ->
                                                                                        new Line(
                                                                                                line
                                                                                                        .number(),
                                                                                                line
                                                                                                        .text(),
                                                                                                line
                                                                                                        .pageNumber()))
                                                                        .toList()))));
    }

    /** Some lines of one processing version of a document. */
    public record LineRange(long processingVersion, List<Line> lines) {
        public LineRange {
            lines = List.copyOf(lines);
        }
    }

    private DocumentView view(DocumentMetadata document, boolean withText) {
        var id = document.documentId();
        var progress = documents.progress(id, document.processingVersion());
        var state = document.extractionState().displayedWith(progress.deliveryState());
        boolean active =
                progress.deliveryState().equals("PENDING")
                        || progress.deliveryState().equals("RUNNING");
        var lines =
                withText && state == ExtractionState.EXTRACTED
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
                id, document.originalName(), document.processingVersion(), state, message, lines);
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
            List<Line> lines) {
        public DocumentView {
            lines = List.copyOf(lines);
        }
    }
}
