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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
