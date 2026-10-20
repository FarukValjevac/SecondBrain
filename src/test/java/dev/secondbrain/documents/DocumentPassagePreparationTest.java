package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

class DocumentPassagePreparationTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final IndexingVersion version = new IndexingVersion(UUID.randomUUID(), 7, 2, NOW);
    private final EventEnvelope request =
            new EventEnvelope(
                    UUID.randomUUID(),
                    ReindexRequestedEvent.TYPE,
                    version.documentId(),
                    7,
                    NOW,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01",
                    Map.of());
    private final ExtractedText text =
            new ExtractedText(
                    version.documentId(), 2, NOW, List.of(new ExtractedText.Line(1, "Note")));
    private final PreparedPassages prepared =
            new PreparedPassages(
                    version,
                    1,
                    List.of(
                            new PreparedPassages.Passage(
                                    1,
                                    "Note",
                                    new PreparedPassages.Location(
                                            1, 0, 1, 4, OptionalInt.empty()))));
    private final JdbcExtractedTextRepository texts = mock(JdbcExtractedTextRepository.class);
    private final PassageSplitter splitter = mock(PassageSplitter.class);
    private final JdbcPassageRepository passages = mock(JdbcPassageRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final DocumentPassagePreparation stage =
            new DocumentPassagePreparation(
                    texts, splitter, passages, events, Clock.fixed(NOW, ZoneOffset.UTC));
    private final TransactionTemplate transaction =
            new TransactionTemplate(new DocumentIndexingServiceTest.CallbackTransactions());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void splitsOutsideTheWriteTransactionAndPublishesAfterNewOrIdenticalPersistence(
            boolean inserted) throws Exception {
        when(texts.findByDocumentVersion(version.documentId(), 2))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return Optional.of(text);
                        });
        when(splitter.split(version, text))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return prepared;
                        });
        var effect = stage.prepare(version, request);
        verifyNoInteractions(passages, events);
        assertThatThrownBy(effect::apply).isInstanceOf(IllegalStateException.class);
        when(passages.insert(prepared))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            return inserted;
                        });
        assertThat(transaction.<EventHandler.Outcome>execute(status -> effect.apply()))
                .isEqualTo(EventHandler.Outcome.COMPLETED);
        var envelope = ArgumentCaptor.forClass(EventEnvelope.class);
        var order = inOrder(passages, events);
        order.verify(passages).insert(prepared);
        order.verify(events).publish(envelope.capture());
        var downstream = envelope.getValue();
        assertThat(downstream.type()).isEqualTo(PassagesPreparedEvent.TYPE);
        assertThat(downstream.documentId()).isEqualTo(version.documentId());
        assertThat(downstream.processingVersion()).isEqualTo(7);
        assertThat(downstream.occurredAt()).isEqualTo(NOW);
        assertThat(downstream.correlationId()).isEqualTo(request.correlationId());
        assertThat(downstream.causationId()).isEqualTo(request.eventId());
        assertThat(downstream.traceParent()).isEqualTo(request.traceParent());
        assertThat(downstream.references()).isEmpty();
    }

    @Test
    void skipsASourceRemovedBetweenPreflightAndRead() throws Exception {
        when(texts.findByDocumentVersion(version.documentId(), 2)).thenReturn(Optional.empty());
        assertThat(stage.prepare(version, request).apply()).isEqualTo(EventHandler.Outcome.SKIPPED);
        verifyNoInteractions(splitter, passages, events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "splitter", "snapshot"})
    void classifiesInvalidStoredContentPolicyLimitsAndSnapshotConflictsAsPermanent(String failureAt)
            throws Exception {
        when(texts.findByDocumentVersion(version.documentId(), 2)).thenReturn(Optional.of(text));
        when(splitter.split(version, text)).thenReturn(prepared);
        var failure = new IllegalStateException("private-study-sentinel");
        switch (failureAt) {
            case "source" ->
                    when(texts.findByDocumentVersion(version.documentId(), 2)).thenThrow(failure);
            case "splitter" -> when(splitter.split(version, text)).thenThrow(failure);
            case "snapshot" -> when(passages.insert(prepared)).thenThrow(failure);
            default -> throw new IllegalArgumentException("Unknown fixture");
        }
        assertThatThrownBy(
                        () -> {
                            var effect = stage.prepare(version, request);
                            transaction.executeWithoutResult(status -> effect.apply());
                        })
                .isInstanceOf(DeliveryFailure.class)
                .hasCause(failure)
                .hasMessageNotContaining("private-study-sentinel");
        verifyNoInteractions(events);
    }

    @Test
    void leavesDatabaseAvailabilityFailuresRetryable() {
        var unavailable = new DataAccessResourceFailureException("private-database-sentinel");
        when(texts.findByDocumentVersion(version.documentId(), 2)).thenThrow(unavailable);
        assertThatThrownBy(() -> stage.prepare(version, request)).isSameAs(unavailable);
        verifyNoInteractions(splitter, passages, events);
    }

    @Test
    void refusesTheWrongEventOrIndexBeforeReadingText() {
        for (var wrong :
                List.of(
                        new EventEnvelope(
                                request.eventId(),
                                TextExtractedEvent.TYPE,
                                version.documentId(),
                                7,
                                NOW,
                                request.correlationId(),
                                null,
                                null,
                                Map.of()),
                        new EventEnvelope(
                                request.eventId(),
                                ReindexRequestedEvent.TYPE,
                                UUID.randomUUID(),
                                7,
                                NOW,
                                request.correlationId(),
                                null,
                                null,
                                Map.of()),
                        new EventEnvelope(
                                request.eventId(),
                                ReindexRequestedEvent.TYPE,
                                version.documentId(),
                                8,
                                NOW,
                                request.correlationId(),
                                null,
                                null,
                                Map.of()))) {
            assertThatThrownBy(() -> stage.prepare(version, wrong))
                    .isInstanceOf(DeliveryFailure.class);
        }
        verifyNoInteractions(texts, splitter, passages, events);
    }

    @Test
    void cancellationPreservesInterruptionAndDoesNotReadText() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> stage.prepare(version, request))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(texts, splitter, passages, events);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void refusesToPrepareInsideACallerTransaction() {
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            try {
                                                stage.prepare(version, request);
                                            } catch (InterruptedException interrupted) {
                                                Thread.currentThread().interrupt();
                                                throw new IllegalStateException(interrupted);
                                            }
                                        }))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(texts, splitter, passages, events);
    }
}
