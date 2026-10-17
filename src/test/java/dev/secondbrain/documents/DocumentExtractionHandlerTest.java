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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class DocumentExtractionHandlerTest {
    private final JdbcDocumentRepository documents = mock(JdbcDocumentRepository.class);
    private final DocumentTextExtractionService extraction =
            mock(DocumentTextExtractionService.class);
    private final DocumentExtractionCompletion completion =
            mock(DocumentExtractionCompletion.class);
    private final DocumentExtractionHandler handler =
            new DocumentExtractionHandler(documents, extraction, completion);
    private final UUID id = UUID.randomUUID();
    private final EventEnvelope event =
            new EventEnvelope(
                    UUID.randomUUID(),
                    DocumentAcceptedEvent.TYPE,
                    id,
                    1,
                    Instant.EPOCH,
                    UUID.randomUUID(),
                    null,
                    null,
                    Map.of());

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preparesOutsideTheEffectAndDefersAllWrites(boolean current) throws Exception {
        var metadata = metadata(1, ExtractionState.QUEUED);
        var text =
                new ExtractedText(id, 1, Instant.EPOCH, List.of(new ExtractedText.Line(1, "Note")));
        when(documents.findById(id)).thenReturn(Optional.of(metadata));
        when(extraction.extract(metadata)).thenReturn(text);
        var effect = handler.prepare(event);
        verifyNoInteractions(completion);
        when(completion.complete(text, event)).thenReturn(current);
        assertThat(effect.apply())
                .isEqualTo(current ? EventHandler.Outcome.COMPLETED : EventHandler.Outcome.SKIPPED);
    }

    @Test
    void skipsMissingStaleAndCompletedWithoutOpeningTheOriginal() throws Exception {
        for (var candidate :
                List.of(
                        Optional.<DocumentMetadata>empty(),
                        Optional.of(metadata(2, ExtractionState.QUEUED)),
                        Optional.of(metadata(1, ExtractionState.EXTRACTED)))) {
            when(documents.findById(id)).thenReturn(candidate);
            assertThat(handler.prepare(event).apply()).isEqualTo(EventHandler.Outcome.SKIPPED);
        }
        verifyNoInteractions(extraction, completion);
    }

    @ParameterizedTest
    @EnumSource(
            value = TextExtractionException.Reason.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = "CANCELLED")
    void translatesExpectedFailuresWithoutWritingDuringPreparation(
            TextExtractionException.Reason reason) {
        var metadata = metadata(1, ExtractionState.QUEUED);
        var failure = new TextExtractionException(reason);
        when(documents.findById(id)).thenReturn(Optional.of(metadata));
        when(extraction.extract(metadata)).thenThrow(failure);
        assertThatThrownBy(() -> handler.prepare(event))
                .isInstanceOf(DeliveryFailure.class)
                .hasCause(failure);
        verifyNoInteractions(completion);
    }

    @Test
    void preservesCancellation() {
        var metadata = metadata(1, ExtractionState.QUEUED);
        when(documents.findById(id)).thenReturn(Optional.of(metadata));
        when(extraction.extract(metadata))
                .thenThrow(new TextExtractionException(TextExtractionException.Reason.CANCELLED));
        try {
            assertThatThrownBy(() -> handler.prepare(event))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsPreparationInsideATransactionBeforeReadingOrWriting() {
        var previous =
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive();
        org.springframework.transaction.support.TransactionSynchronizationManager
                .setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> handler.prepare(event))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(documents, extraction, completion);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .setActualTransactionActive(previous);
        }
    }

    private DocumentMetadata metadata(long version, ExtractionState state) {
        return new DocumentMetadata(
                id,
                "notes.txt",
                DocumentType.TXT,
                UUID.randomUUID(),
                "a".repeat(64),
                4,
                version,
                state,
                Instant.EPOCH,
                Instant.EPOCH);
    }
}
