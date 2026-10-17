package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.secondbrain.events.DeliveryFailure;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;
import dev.secondbrain.events.EventPublisher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class DocumentDeletionHandlerTest {
    private final JdbcDocumentRepository documents = mock(JdbcDocumentRepository.class);
    private final OriginalFileStore originals = mock(OriginalFileStore.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final DocumentDeletionHandler handler =
            new DocumentDeletionHandler(documents, originals, events, transactions);
    private final UUID id = UUID.randomUUID();
    private final UUID storage = UUID.randomUUID();
    private final EventEnvelope event =
            new EventEnvelope(
                    UUID.randomUUID(),
                    DocumentDeletionRequestedEvent.TYPE,
                    id,
                    2,
                    Instant.EPOCH,
                    UUID.randomUUID(),
                    null,
                    null,
                    Map.of());

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deletesTheOriginalBeforeTheEffectAndLocksOldDeliveriesBeforeTheDocument(boolean current)
            throws Exception {
        when(documents.deletedStorageId(id, 2)).thenReturn(Optional.of(storage));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(documents.finishDeletion(id, 2, storage)).thenReturn(current);

        var effect = handler.prepare(event);
        verifyNoInteractions(events, transactions);
        var order = inOrder(originals, events, documents);
        order.verify(originals).delete(storage);
        assertThat(effect.apply())
                .isEqualTo(current ? EventHandler.Outcome.COMPLETED : EventHandler.Outcome.SKIPPED);
        order.verify(events).discardSuperseded(id, 2);
        order.verify(documents).finishDeletion(id, 2, storage);
    }

    @Test
    void skipsAbsentVisibleOrSupersededDocumentsWithoutDeletingFiles() throws Exception {
        when(documents.deletedStorageId(id, 2)).thenReturn(Optional.empty());
        assertThat(handler.prepare(event).apply()).isEqualTo(EventHandler.Outcome.SKIPPED);
        verifyNoInteractions(originals, events, transactions);
    }

    @Test
    void leavesDatabaseFinalizationUntouchedOnStorageFailure() throws Exception {
        when(documents.deletedStorageId(id, 2)).thenReturn(Optional.of(storage));
        var failure = new IOException("sentinel-private-path");
        doThrow(failure).when(originals).delete(storage);
        assertThatThrownBy(() -> handler.prepare(event)).isSameAs(failure);
        verifyNoInteractions(events, transactions);
    }

    @Test
    void validatesTheTypeAndSchemaBeforeAccessingStorage() {
        for (var type :
                new EventEnvelope.Type[] {
                    DocumentAcceptedEvent.TYPE,
                    new EventEnvelope.Type("DocumentDeletionRequested", 2)
                }) {
            var invalid =
                    new EventEnvelope(
                            event.eventId(),
                            type,
                            id,
                            2,
                            Instant.EPOCH,
                            event.correlationId(),
                            null,
                            null,
                            Map.of());
            assertThatThrownBy(() -> handler.prepare(invalid)).isInstanceOf(DeliveryFailure.class);
        }
        verifyNoInteractions(documents, originals, events, transactions);
    }

    @Test
    void refusesFilesystemWorkInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> handler.prepare(event))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(documents, originals, events, transactions);
    }

    @Test
    void preservesInterruptionWithoutStartingCleanup() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> handler.prepare(event))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verifyNoInteractions(documents, originals, events, transactions);
    }
}
