package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.secondbrain.events.EventPublisher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

class DocumentInspectionServiceTest {
    private final JdbcDocumentRepository documents = mock(JdbcDocumentRepository.class);
    private final JdbcExtractedTextRepository texts = mock(JdbcExtractedTextRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final DocumentInspectionService service =
            new DocumentInspectionService(
                    documents, texts, events, transactions, Clock.systemUTC());

    @BeforeEach
    void transaction() {
        when(transactions.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
    }

    @Test
    void sourceReadsUseAFreshBoundedReadOnlyTransactionWithoutOtherContentIo() {
        var id = UUID.randomUUID();
        var passage = UUID.randomUUID();
        var source =
                new DocumentInspectionService.PassageSource(
                        id,
                        passage,
                        7,
                        "sentinel-private-name",
                        "sentinel-private-text",
                        new DocumentInspectionService.SourceLocation(
                                1, 0, 1, 4, OptionalInt.empty()));
        when(documents.source(id, passage)).thenReturn(Optional.of(source));
        assertThat(service.source(id, passage)).contains(source);
        var definition =
                org.mockito.ArgumentCaptor.forClass(
                        org.springframework.transaction.TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(
                        org.springframework.transaction.TransactionDefinition
                                .PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getValue().getIsolationLevel())
                .isEqualTo(
                        org.springframework.transaction.TransactionDefinition
                                .ISOLATION_READ_COMMITTED);
        assertThat(definition.getValue().getTimeout()).isEqualTo(5);
        verify(documents).source(id, passage);
        verifyNoInteractions(texts, events);
        assertThat(source.toString())
                .doesNotContain("sentinel-private", id.toString(), passage.toString());
    }

    @Test
    void unavailableSourceIsExplicitAndNeverLoadsFullText() {
        var id = UUID.randomUUID();
        var passage = UUID.randomUUID();
        when(documents.source(id, passage)).thenReturn(Optional.empty());
        assertThat(service.source(id, passage)).isEmpty();
        verifyNoInteractions(texts, events);
    }

    @Test
    void missingSourceIdsAreRejectedBeforeAnyIo() {
        assertThatThrownBy(() -> service.source(null, UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.source(UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(documents, texts, events, transactions);
    }

    @Test
    void sourceProjectionBoundsTextAndRejectsInvalidCoordinates() {
        var id = UUID.randomUUID();
        var passage = UUID.randomUUID();
        var location =
                new DocumentInspectionService.SourceLocation(1, 0, 1, 1, OptionalInt.empty());
        for (var text : List.of("", " ", "x".repeat(1201), "bad\u0000", "\uD800")) {
            assertThatThrownBy(
                            () ->
                                    new DocumentInspectionService.PassageSource(
                                            id, passage, 1, "name", text, location))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(
                        () ->
                                new DocumentInspectionService.PassageSource(
                                        id, passage, 0, "name", "text", location))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new DocumentInspectionService.SourceLocation(
                                        0, 0, 1, 1, OptionalInt.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new DocumentInspectionService.SourceLocation(
                                        1, 2, 1, 2, OptionalInt.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new DocumentInspectionService.SourceLocation(
                                        1, 0, 1, 1, OptionalInt.of(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void namesAreImmutableVisibleLabelsWithoutTextOrProgressReads() {
        var id = UUID.randomUUID();
        var mutable = new java.util.HashMap<UUID, String>();
        mutable.put(id, "Notes.md");
        when(documents.names(Set.of(id))).thenReturn(mutable);
        var names = service.names(Set.of(id));
        mutable.clear();
        assertThat(names).containsExactlyEntriesOf(Map.of(id, "Notes.md"));
        assertThatThrownBy(() -> names.clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(documents).names(Set.of(id));
        verifyNoInteractions(texts, events);
    }

    @Test
    void emptyNamesNeedNoTransactionOrRepository() {
        assertThat(service.names(Set.of())).isEmpty();
        verifyNoInteractions(documents, texts, events, transactions);
    }

    @Test
    void nameLookupAccepts100IdsAndRejectsLargerBatchesBeforeIo() {
        var ids =
                java.util.stream.IntStream.range(0, 100)
                        .mapToObj(i -> new UUID(0, i))
                        .collect(java.util.stream.Collectors.toSet());
        when(documents.names(ids)).thenReturn(Map.of());
        assertThat(service.names(ids)).isEmpty();
        verify(documents).names(ids);
        ids.add(UUID.randomUUID());
        assertThatThrownBy(() -> service.names(ids)).isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoMoreInteractions(documents);
        verifyNoInteractions(texts, events);
    }

    @Test
    void confirmationSummaryDoesNotRequireStoredText() {
        var id = UUID.randomUUID();
        when(documents.findById(id))
                .thenReturn(
                        Optional.of(
                                new DocumentMetadata(
                                        id,
                                        "Notes.md",
                                        DocumentType.MARKDOWN,
                                        UUID.randomUUID(),
                                        "a".repeat(64),
                                        10,
                                        1,
                                        ExtractionState.EXTRACTED,
                                        Instant.EPOCH,
                                        Instant.EPOCH)));
        when(documents.progress(id, 1))
                .thenReturn(new JdbcDocumentRepository.Progress(Optional.empty(), "COMPLETED"));
        var summary = service.summary(id).orElseThrow();
        assertThat(summary.name()).isEqualTo("Notes.md");
        assertThat(summary.state()).isEqualTo(ExtractionState.EXTRACTED);
        verifyNoInteractions(texts, events);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exposesStoredPageReferencesWithoutInventingPagesForTextFiles(boolean paged) {
        var id = UUID.randomUUID();
        when(documents.findById(id))
                .thenReturn(
                        Optional.of(
                                new DocumentMetadata(
                                        id,
                                        paged ? "notes.pdf" : "notes.md",
                                        paged ? DocumentType.PDF : DocumentType.MARKDOWN,
                                        UUID.randomUUID(),
                                        "a".repeat(64),
                                        10,
                                        1,
                                        ExtractionState.EXTRACTED,
                                        Instant.EPOCH,
                                        Instant.EPOCH)));
        when(documents.progress(id, 1))
                .thenReturn(new JdbcDocumentRepository.Progress(Optional.empty(), "COMPLETED"));
        var firstPage = paged ? OptionalInt.of(1) : OptionalInt.empty();
        var blankPage = paged ? OptionalInt.of(2) : OptionalInt.empty();
        var lastPage = paged ? OptionalInt.of(3) : OptionalInt.empty();
        when(texts.findByDocumentVersion(id, 1))
                .thenReturn(
                        Optional.of(
                                new ExtractedText(
                                        id,
                                        1,
                                        Instant.EPOCH,
                                        List.of(
                                                new ExtractedText.Line(
                                                        1,
                                                        "Café <script>reference</script>",
                                                        firstPage),
                                                new ExtractedText.Line(2, "", blankPage),
                                                new ExtractedText.Line(
                                                        3, "Last page", lastPage)))));

        var view = service.find(id).orElseThrow();

        assertThat(view.lines())
                .containsExactly(
                        new DocumentInspectionService.Line(
                                1, "Café <script>reference</script>", firstPage),
                        new DocumentInspectionService.Line(2, "", blankPage),
                        new DocumentInspectionService.Line(3, "Last page", lastPage));
        verifyNoInteractions(events);
    }

    @Test
    void boundsThePageAndUsesCurrentDeliveryStatusWithoutLoadingText() {
        var rows = new ArrayList<JdbcDocumentRepository.LibraryRow>();
        rows.add(row(ExtractionState.QUEUED, "RUNNING"));
        rows.add(row(ExtractionState.QUEUED, "FAILED"));
        rows.add(row(ExtractionState.FAILED, "PENDING"));
        rows.add(row(ExtractionState.EXTRACTED, "COMPLETED"));
        while (rows.size() < 51) rows.add(row(ExtractionState.QUEUED, ""));
        when(documents.list(51, 100L)).thenReturn(rows);

        var page = service.list(2);

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.documents()).hasSize(50);
        assertThat(page.documents().subList(0, 4))
                .extracting(DocumentInspectionService.DocumentSummary::state)
                .containsExactly(
                        ExtractionState.PROCESSING,
                        ExtractionState.FAILED,
                        ExtractionState.PROCESSING,
                        ExtractionState.EXTRACTED);
        assertThat(page.documents().getFirst().documentId())
                .isEqualTo(rows.getFirst().documentId());
        assertThat(page.documents())
                .noneMatch(document -> document.documentId().equals(rows.getLast().documentId()));
        assertThatThrownBy(() -> page.documents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        verify(documents).list(51, 100L);
        verifyNoInteractions(texts, events);
    }

    @Test
    void lastAndEmptyPagesHaveNoNextLinkAndLargeOffsetsDoNotOverflow() {
        when(documents.list(51, 0L)).thenReturn(List.of(row(ExtractionState.EXTRACTED, "")));
        when(documents.list(51, 50L * Integer.MAX_VALUE)).thenReturn(List.of());

        assertThat(service.list(0).hasNext()).isFalse();
        var empty = service.list(Integer.MAX_VALUE);
        assertThat(empty.documents()).isEmpty();
        assertThat(empty.hasNext()).isFalse();
        verify(documents).list(51, 50L * Integer.MAX_VALUE);
        verifyNoInteractions(texts, events);
    }

    @Test
    void rejectsNegativePagesBeforeStartingDatabaseWork() {
        assertThatThrownBy(() -> service.list(-1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(documents, texts, events, transactions);
    }

    private static JdbcDocumentRepository.LibraryRow row(ExtractionState state, String delivery) {
        return new JdbcDocumentRepository.LibraryRow(
                UUID.randomUUID(), "Notes.md", state, Instant.EPOCH, delivery);
    }
}
