package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.secondbrain.events.EventPublisher;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.UUID;

class DocumentIndexingReconciliationTest {
    @Test
    void usesABoundedBatchAndLeavesRequestsUnconsumedWithoutAPassageStage() {
        var indexes = mock(JdbcIndexingRepository.class);
        var indexing = mock(DocumentIndexingService.class);
        var events = mock(EventPublisher.class);
        var id = UUID.randomUUID();
        when(indexes.findUnenrolled(100)).thenReturn(List.of(id));
        new DocumentIndexingReconciliation(
                        indexes,
                        indexing,
                        events,
                        new StaticListableBeanFactory()
                                .getBeanProvider(DocumentPassageHandler.class))
                .reconcile();
        verify(indexing).enroll(eq(id), any(UUID.class));
        verifyNoInteractions(events);
    }

    @Test
    void aFailedDatabaseBatchCanBeRetriedByTheNextScheduledRun() {
        var indexes = mock(JdbcIndexingRepository.class);
        var events = mock(EventPublisher.class);
        var indexing = mock(DocumentIndexingService.class);
        when(indexes.findUnenrolled(100))
                .thenThrow(new IllegalStateException("test-only-private-failure"))
                .thenReturn(List.of());
        var reconciliation =
                new DocumentIndexingReconciliation(
                        indexes,
                        indexing,
                        events,
                        new StaticListableBeanFactory()
                                .getBeanProvider(DocumentPassageHandler.class));
        assertThatCode(reconciliation::reconcile).doesNotThrowAnyException();
        assertThatCode(reconciliation::reconcile).doesNotThrowAnyException();
        verifyNoInteractions(indexing, events);
    }
}
