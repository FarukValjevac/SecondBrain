package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventPublisher;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

class DocumentExtractionCompletionTest {
    private static final UUID DOCUMENT_ID = UUID.randomUUID();

    @ParameterizedTest
    @MethodSource("unrelatedEvents")
    void rejectsAnUnrelatedAcceptanceBeforeAnyDatabaseWork(EventEnvelope event) {
        var documents = mock(JdbcDocumentRepository.class);
        var texts = mock(JdbcExtractedTextRepository.class);
        var events = mock(EventPublisher.class);
        var transactions = mock(PlatformTransactionManager.class);
        var completion =
                new DocumentExtractionCompletion(
                        documents, texts, events, Clock.systemUTC(), transactions);
        var text =
                new ExtractedText(
                        DOCUMENT_ID,
                        1,
                        Instant.EPOCH,
                        List.of(new ExtractedText.Line(1, "Private text")));

        assertThatThrownBy(() -> completion.complete(text, event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Extracted text must match its acceptance event");
        verifyNoInteractions(documents, texts, events, transactions);
    }

    private static Stream<EventEnvelope> unrelatedEvents() {
        return Stream.of(
                event(new EventEnvelope.Type("DocumentAccepted", 2), DOCUMENT_ID, 1),
                event(DocumentAcceptedEvent.TYPE, UUID.randomUUID(), 1),
                event(DocumentAcceptedEvent.TYPE, DOCUMENT_ID, 2));
    }

    private static EventEnvelope event(EventEnvelope.Type type, UUID documentId, long version) {
        return new EventEnvelope(
                UUID.randomUUID(),
                type,
                documentId,
                version,
                Instant.EPOCH,
                UUID.randomUUID(),
                null,
                null,
                Map.of());
    }
}
