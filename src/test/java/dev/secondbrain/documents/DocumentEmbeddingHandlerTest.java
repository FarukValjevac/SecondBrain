package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.secondbrain.embeddings.EmbeddingFailure;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;
import dev.secondbrain.events.DeliveryFailure;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class DocumentEmbeddingHandlerTest {
    private final EmbeddingModel model = new EmbeddingModel("@cf/baai/bge-m3", 1024);
    private final IndexingVersion version =
            new IndexingVersion(UUID.randomUUID(), 7, 2, Instant.EPOCH);
    private final EventEnvelope event =
            new EventEnvelope(
                    UUID.randomUUID(),
                    PassagesPreparedEvent.TYPE,
                    version.documentId(),
                    7,
                    Instant.EPOCH,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    Map.of());
    private final JdbcIndexingRepository indexes = mock(JdbcIndexingRepository.class);
    private final JdbcEmbeddingRepository embeddings = mock(JdbcEmbeddingRepository.class);
    private final EmbeddingProvider provider = mock(EmbeddingProvider.class);
    private final TransactionTemplate transaction =
            new TransactionTemplate(new DocumentIndexingServiceTest.CallbackTransactions());

    private DocumentEmbeddingHandler handler() {
        when(provider.model()).thenReturn(model);
        when(indexes.findEligibleDesired(version.documentId())).thenReturn(Optional.of(version));
        when(indexes.lockEligibleDesired(version.documentId(), 7)).thenReturn(Optional.of(version));
        return new DocumentEmbeddingHandler(indexes, embeddings, provider, 2);
    }

    @Test
    void savesEachBatchInsideACheckpointAndChecksCompletenessOnlyAtAcknowledgment()
            throws Exception {
        var handler = handler();
        var first = new JdbcEmbeddingRepository.Input(UUID.randomUUID(), "private-first");
        var second = new JdbcEmbeddingRepository.Input(UUID.randomUUID(), "private-second");
        when(embeddings.findPendingBatch(version, model, 2))
                .thenReturn(List.of(first, second))
                .thenReturn(List.of(second))
                .thenReturn(List.of());
        when(provider.embed(any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            List<String> input = call.getArgument(0);
                            return input.stream().map(ignored -> vector()).toList();
                        });
        when(embeddings.insertBatch(eq(version), eq(model), any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            return 1;
                        });
        var effect = handler.prepare(event, this::save);
        verify(embeddings, times(2)).insertBatch(eq(version), eq(model), any());
        verify(embeddings, never()).requireComplete(any(), any());
        assertThat(transaction.<EventHandler.Outcome>execute(status -> effect.apply()))
                .isEqualTo(EventHandler.Outcome.COMPLETED);
        verify(embeddings).requireComplete(version, model);
        var order = inOrder(provider, embeddings);
        order.verify(provider).embed(List.of("private-first", "private-second"));
        order.verify(embeddings).insertBatch(eq(version), eq(model), any());
        order.verify(provider).embed(List.of("private-second"));
        order.verify(embeddings).insertBatch(eq(version), eq(model), any());
    }

    @Test
    void anAlreadyCompleteGenerationDoesNotRepeatProviderCalls() throws Exception {
        var handler = handler();
        when(embeddings.findPendingBatch(version, model, 2)).thenReturn(List.of());
        var effect = handler.prepare(event, this::save);
        assertThat(transaction.<EventHandler.Outcome>execute(status -> effect.apply()))
                .isEqualTo(EventHandler.Outcome.COMPLETED);
        verify(provider, never()).embed(any());
        verify(embeddings).requireComplete(version, model);
    }

    @Test
    void obsoleteRequestsSkipBeforeReadingPrivateText() throws Exception {
        var handler = handler();
        when(indexes.findEligibleDesired(version.documentId())).thenReturn(Optional.empty());
        assertThat(handler.prepare(event, this::save).apply())
                .isEqualTo(EventHandler.Outcome.SKIPPED);
        verifyNoInteractions(embeddings);
        verify(provider, never()).embed(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void stopsAfterLeaseLossOrDocumentBecomingIneligible(boolean leaseLost) throws Exception {
        var handler = handler();
        pending();
        when(provider.embed(any())).thenReturn(List.of(vector()));
        when(indexes.lockEligibleDesired(version.documentId(), 7)).thenReturn(Optional.empty());
        EventHandler.Checkpoint checkpoint = leaseLost ? ignored -> Optional.empty() : this::save;
        assertThat(handler.prepare(event, checkpoint).apply())
                .isEqualTo(EventHandler.Outcome.SKIPPED);
        verify(embeddings, never()).insertBatch(any(), any(), any());
        verify(embeddings, times(1)).findPendingBatch(version, model, 2);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void invalidProviderResultsNeverReachStorage(int kind) throws Exception {
        var handler = handler();
        pending();
        List<EmbeddingVector> invalid =
                switch (kind) {
                    case 0 -> null;
                    case 1 -> List.of();
                    default -> Arrays.asList((EmbeddingVector) null);
                };
        when(provider.embed(any())).thenReturn(invalid);
        assertThatThrownBy(() -> handler.prepare(event, this::save))
                .isInstanceOf(DeliveryFailure.class);
        verify(embeddings, never()).insertBatch(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesProviderClassificationWithoutSavingAnIncompleteBatch(boolean retryable)
            throws Exception {
        var handler = handler();
        pending();
        var failure = mock(EmbeddingFailure.class);
        when(failure.retryable()).thenReturn(retryable);
        when(failure.retryAt()).thenReturn(Optional.of(Instant.parse("2030-01-02T00:00:00Z")));
        when(provider.embed(any())).thenThrow(failure);
        assertThatThrownBy(() -> handler.prepare(event, this::save))
                .isInstanceOf(DeliveryFailure.class)
                .hasCause(failure);
        verify(embeddings, never()).insertBatch(any(), any(), any());
    }

    @Test
    void refusesWorkInsideAnOpenTransactionOrWithoutCheckpoints() {
        var handler = handler();
        assertThatThrownBy(() -> handler.prepare(event)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                transaction.execute(
                                        status -> {
                                            try {
                                                return handler.prepare(event, this::save);
                                            } catch (InterruptedException unexpected) {
                                                throw new AssertionError(unexpected);
                                            }
                                        }))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(embeddings);
    }

    @Test
    void preservesInterruptionAndDoesNotSaveProviderResults() throws Exception {
        var handler = handler();
        pending();
        when(provider.embed(any()))
                .thenAnswer(
                        call -> {
                            Thread.currentThread().interrupt();
                            return List.of(vector());
                        });
        try {
            assertThatThrownBy(() -> handler.prepare(event, this::save))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(embeddings, never()).insertBatch(any(), any(), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void finalizationRechecksSourceAndDoesNotTreatPartialStorageAsComplete() throws Exception {
        var handler = handler();
        when(embeddings.findPendingBatch(version, model, 2)).thenReturn(List.of());
        var effect = handler.prepare(event, this::save);
        doThrow(new IllegalStateException("Incomplete generation"))
                .when(embeddings)
                .requireComplete(version, model);
        assertThatThrownBy(() -> transaction.execute(status -> effect.apply()))
                .isInstanceOf(DeliveryFailure.class);
        when(indexes.lockEligibleDesired(version.documentId(), 7)).thenReturn(Optional.empty());
        assertThat(transaction.<EventHandler.Outcome>execute(status -> effect.apply()))
                .isEqualTo(EventHandler.Outcome.SKIPPED);
    }

    private void pending() {
        when(embeddings.findPendingBatch(version, model, 2))
                .thenReturn(
                        List.of(
                                new JdbcEmbeddingRepository.Input(
                                        UUID.randomUUID(), "private-source")));
    }

    private Optional<EventHandler.Outcome> save(EventHandler.Effect effect) {
        return Optional.ofNullable(transaction.execute(status -> effect.apply()));
    }

    private EmbeddingVector vector() {
        float[] values = new float[1024];
        values[0] = 1;
        return new EmbeddingVector(model, values);
    }
}
