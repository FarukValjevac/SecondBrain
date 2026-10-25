package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.events.DeliveryFailure;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;
import dev.secondbrain.events.EventPublisher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class DocumentIndexPublicationTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private final EmbeddingModel model = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
    private final IndexingVersion version = new IndexingVersion(UUID.randomUUID(), 7, 2, NOW);
    private final EventEnvelope cause = event(PassagesPreparedEvent.TYPE, version.documentId(), 7);
    private final JdbcIndexingRepository indexes = mock(JdbcIndexingRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final DocumentIndexingServiceTest.CallbackTransactions manager =
            new DocumentIndexingServiceTest.CallbackTransactions();
    private final TransactionTemplate transaction = new TransactionTemplate(manager);
    private final DocumentIndexPublication publication =
            new DocumentIndexPublication(
                    indexes, events, model, Clock.fixed(NOW, ZoneOffset.UTC), manager);

    @ParameterizedTest
    @EnumSource(JdbcIndexingRepository.Activation.class)
    void publishesOnlyAfterEligibleCompleteActivation(
            JdbcIndexingRepository.Activation activation) {
        when(indexes.activate(version, model, NOW)).thenReturn(activation);
        if (activation == JdbcIndexingRepository.Activation.INCOMPLETE) {
            assertThatThrownBy(
                            () ->
                                    transaction.execute(
                                            status -> publication.publish(version, cause)))
                    .isInstanceOf(DeliveryFailure.class);
            verifyNoInteractions(events);
        } else {
            var outcome = transaction.execute(status -> publication.publish(version, cause));
            if (activation == JdbcIndexingRepository.Activation.INELIGIBLE) {
                assertThat(outcome).isEqualTo(EventHandler.Outcome.SKIPPED);
                verifyNoInteractions(events);
            } else {
                assertThat(outcome).isEqualTo(EventHandler.Outcome.COMPLETED);
                var emitted = ArgumentCaptor.forClass(EventEnvelope.class);
                verify(events).publish(emitted.capture());
                var event = emitted.getValue();
                assertThat(event.type()).isEqualTo(IndexPublishedEvent.TYPE);
                assertThat(event.documentId()).isEqualTo(version.documentId());
                assertThat(event.processingVersion()).isEqualTo(7);
                assertThat(event.correlationId()).isEqualTo(cause.correlationId());
                assertThat(event.causationId()).isEqualTo(cause.eventId());
                assertThat(event.traceParent()).isEqualTo(cause.traceParent());
                assertThat(event.references()).isEmpty();
            }
        }
    }

    @Test
    void refusesIndependentPublicationWithoutACallerTransaction() {
        assertThatThrownBy(() -> publication.publish(version, cause))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(indexes, events);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void refusesMismatchedDocumentGenerationOrEvent(int mismatch) {
        var invalid =
                switch (mismatch) {
                    case 0 -> event(PassagesPreparedEvent.TYPE, UUID.randomUUID(), 7);
                    case 1 -> event(PassagesPreparedEvent.TYPE, version.documentId(), 8);
                    default -> event(new EventEnvelope.Type("Other", 1), version.documentId(), 7);
                };
        assertThatThrownBy(
                        () -> transaction.execute(status -> publication.publish(version, invalid)))
                .isInstanceOf(DeliveryFailure.class);
        verifyNoInteractions(indexes, events);
    }

    @Test
    void recoveryHandlerReadsBeforeItsFencedEffectAndRechecksAtActivation() {
        var request = event(IndexPublicationRequestedEvent.TYPE, version.documentId(), 7);
        when(indexes.findEligibleDesired(version.documentId())).thenReturn(Optional.of(version));
        when(indexes.lockEligibleDesired(version.documentId(), 7)).thenReturn(Optional.of(version));
        when(indexes.completedEmbeddingSource(version, request.causationId())).thenReturn(true);
        var effect = publication.prepare(request);
        verifyNoInteractions(events);
        when(indexes.activate(version, model, NOW))
                .thenReturn(JdbcIndexingRepository.Activation.INELIGIBLE);
        var result = transaction.execute(status -> effect.apply());
        assertThat(result).isEqualTo(EventHandler.Outcome.SKIPPED);
        verifyNoInteractions(events);
    }

    @Test
    void anExistingRecoveryIntentCannotBypassUnfinishedOrFailedEmbeddingWork() {
        var request = event(IndexPublicationRequestedEvent.TYPE, version.documentId(), 7);
        when(indexes.lockEligibleDesired(version.documentId(), 7)).thenReturn(Optional.of(version));
        when(indexes.completedEmbeddingSource(version, request.causationId())).thenReturn(false);
        var result = transaction.execute(status -> publication.publish(version, request));
        assertThat(result).isEqualTo(EventHandler.Outcome.SKIPPED);
        verify(indexes, never()).activate(any(), any(), any());
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void recoveryEnrollmentRechecksStateAndPreservesItsCause(boolean stillEligible) {
        var candidate =
                new JdbcIndexingRepository.PublicationRecovery(
                        version, cause.eventId(), cause.correlationId(), cause.traceParent());
        when(indexes.findPublicationRecovery(model, 100)).thenReturn(List.of(candidate));
        when(indexes.lockEligibleDesired(version.documentId(), 7)).thenReturn(Optional.of(version));
        when(indexes.canRecoverPublication(version, model)).thenReturn(stillEligible);
        when(events.publish(any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            EventEnvelope intent = call.getArgument(0);
                            assertThat(intent.type())
                                    .isEqualTo(IndexPublicationRequestedEvent.TYPE);
                            assertThat(intent.correlationId()).isEqualTo(cause.correlationId());
                            assertThat(intent.causationId()).isEqualTo(cause.eventId());
                            assertThat(intent.references()).isEmpty();
                            return intent.eventId();
                        });
        publication.reconcile();
        if (stillEligible) verify(events).publish(any());
        else verify(events, never()).publish(any());
        verify(events)
                .scheduleMissing(
                        IndexPublicationRequestedEvent.TYPE, DocumentIndexPublication.ID, 100);
    }

    private EventEnvelope event(EventEnvelope.Type type, UUID document, long number) {
        return new EventEnvelope(
                UUID.randomUUID(),
                type,
                document,
                number,
                NOW,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01",
                Map.of());
    }
}
