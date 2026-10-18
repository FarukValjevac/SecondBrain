package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.secondbrain.events.DeliveryFailure;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class DocumentIndexingHandlerTest {
    private final JdbcIndexingRepository indexes = mock(JdbcIndexingRepository.class);
    private final DocumentPassageHandler.Stage stage = mock(DocumentPassageHandler.Stage.class);

    @Test
    void rechecksTheDesiredVersionAfterPreparationAndSkipsSupersededWork() throws Exception {
        var manager = new DocumentIndexingServiceTest.CallbackTransactions();
        var handler = new DocumentPassageHandler(indexes, stage, manager);
        var event = event(ReindexRequestedEvent.TYPE, 7);
        var version = new IndexingVersion(event.documentId(), 7, 2, event.occurredAt());
        var effect = mock(EventHandler.Effect.class);
        when(indexes.findEligibleDesired(event.documentId())).thenReturn(Optional.of(version));
        when(stage.prepare(version, event)).thenReturn(effect);
        var prepared = handler.prepare(event);
        when(indexes.lockEligibleDesired(event.documentId(), 7)).thenReturn(Optional.empty());

        assertThat(
                        new TransactionTemplate(manager)
                                .<EventHandler.Outcome>execute(status -> prepared.apply()))
                .isEqualTo(EventHandler.Outcome.SKIPPED);
        verifyNoInteractions(effect);
    }

    @Test
    void appliesOnlyThePreparedCurrentSourceInsideACallerTransaction() throws Exception {
        var manager = new DocumentIndexingServiceTest.CallbackTransactions();
        var handler = new DocumentPassageHandler(indexes, stage, manager);
        var event = event(ReindexRequestedEvent.TYPE, 7);
        var version = new IndexingVersion(event.documentId(), 7, 2, event.occurredAt());
        when(indexes.findEligibleDesired(event.documentId())).thenReturn(Optional.of(version));
        when(indexes.lockEligibleDesired(event.documentId(), 7)).thenReturn(Optional.of(version));
        when(stage.prepare(version, event)).thenReturn(() -> EventHandler.Outcome.COMPLETED);
        var prepared = handler.prepare(event);
        assertThatThrownBy(prepared::apply)
                .isInstanceOf(
                        org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(
                        new TransactionTemplate(manager)
                                .<EventHandler.Outcome>execute(status -> prepared.apply()))
                .isEqualTo(EventHandler.Outcome.COMPLETED);
    }

    @Test
    void doesNotPrepareAbsentOrDelayedIndexVersionsAndRejectsWrongSchemas() throws Exception {
        var handler =
                new DocumentPassageHandler(
                        indexes, stage, new DocumentIndexingServiceTest.CallbackTransactions());
        var event = event(ReindexRequestedEvent.TYPE, 1);
        when(indexes.findEligibleDesired(event.documentId()))
                .thenReturn(
                        Optional.of(
                                new IndexingVersion(event.documentId(), 2, 1, event.occurredAt())));
        assertThat(handler.prepare(event).apply()).isEqualTo(EventHandler.Outcome.SKIPPED);
        assertThatThrownBy(
                        () ->
                                handler.prepare(
                                        event(new EventEnvelope.Type("ReindexRequested", 2), 1)))
                .isInstanceOf(DeliveryFailure.class);
        verifyNoInteractions(stage);
    }

    @Test
    void extractionHandoffKeepsTheOriginalEnvelopeAndSkipsStaleFacts() {
        var service = mock(DocumentIndexingService.class);
        var handler = new DocumentIndexingHandler(service);
        var event = event(TextExtractedEvent.TYPE, 2);
        when(service.enroll(event)).thenReturn(Optional.empty());
        assertThat(handler.prepare(event).apply()).isEqualTo(EventHandler.Outcome.SKIPPED);
        assertThatThrownBy(() -> handler.prepare(event(ReindexRequestedEvent.TYPE, 2)))
                .isInstanceOf(DeliveryFailure.class);
    }

    private static EventEnvelope event(EventEnvelope.Type type, long version) {
        return new EventEnvelope(
                UUID.randomUUID(),
                type,
                UUID.randomUUID(),
                version,
                Instant.parse("2026-01-01T00:00:00Z"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                Map.of());
    }
}
