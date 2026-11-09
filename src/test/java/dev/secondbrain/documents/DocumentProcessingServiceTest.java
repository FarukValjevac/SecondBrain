package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;
import dev.secondbrain.events.EventRetryService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

class DocumentProcessingServiceTest {
    private final UUID id = UUID.randomUUID();
    private final UUID delivery = UUID.randomUUID();
    private final JdbcProcessingRepository rows = mock(JdbcProcessingRepository.class);
    private final JdbcDocumentRepository documents = mock(JdbcDocumentRepository.class);
    private final EventRetryService retries = mock(EventRetryService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Instant now = Instant.parse("2030-01-01T00:00:00Z");
    private final EventHandler handler = mock(EventHandler.class);

    @BeforeEach
    void transactions() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(handler.id()).thenReturn(DocumentEmbeddingHandler.ID);
        when(handler.eventType()).thenReturn(new EventEnvelope.Type("PassagesPrepared", 1));
    }

    private DocumentProcessingService service(boolean polling) {
        return new DocumentProcessingService(
                rows,
                documents,
                retries,
                () -> List.of(handler),
                polling,
                transactions,
                Clock.fixed(now, ZoneOffset.UTC),
                new DocumentTelemetry(meters, ObservationRegistry.NOOP));
    }

    private JdbcProcessingRepository.Row row(String state, int attempts) {
        return new JdbcProcessingRepository.Row(
                id,
                ExtractionState.EXTRACTED,
                Optional.empty(),
                1L,
                null,
                20,
                3,
                1,
                delivery,
                DocumentEmbeddingHandler.ID,
                state,
                2,
                attempts,
                1,
                Optional.of(now.plusSeconds(60)),
                "PassagesPrepared",
                1);
    }

    @ParameterizedTest
    @CsvSource({"PENDING,QUEUED", "RUNNING,PROCESSING", "FAILED,FAILED"})
    void presentsSavedStageCountsAndSafeMessages(
            String stored, DocumentProcessingService.State expected) {
        when(rows.read(Set.of(id))).thenReturn(List.of(row(stored, 5)));
        var result = service(true).read(Set.of(id)).get(id);
        assertThat(result.state()).isEqualTo(expected);
        assertThat(result.stage()).isEqualTo(DocumentProcessingService.Stage.EMBEDDINGS);
        assertThat(result.lines()).isEqualTo(20);
        assertThat(result.passages()).isEqualTo(3);
        assertThat(result.vectors()).isEqualTo(1);
        assertThat(result.message()).doesNotContain("private", "%");
        assertThat(result.retry().isPresent())
                .isEqualTo(expected == DocumentProcessingService.State.FAILED);
        assertThat(result.eligibleAt().isPresent()).isEqualTo(stored.equals("PENDING"));
    }

    @ParameterizedTest
    @CsvSource({"false,EXTRACTION", "true,PASSAGES"})
    void doesNotMistakeExtractionForACompleteSearchIndex(
            boolean extracted, DocumentProcessingService.Stage stage) {
        when(rows.read(Set.of(id)))
                .thenReturn(
                        List.of(
                                new JdbcProcessingRepository.Row(
                                        id,
                                        extracted
                                                ? ExtractionState.EXTRACTED
                                                : ExtractionState.QUEUED,
                                        Optional.empty(),
                                        null,
                                        null,
                                        extracted ? 10 : 0,
                                        0,
                                        0,
                                        null,
                                        null,
                                        null,
                                        0,
                                        0,
                                        0,
                                        Optional.empty(),
                                        null,
                                        0)));
        when(handler.id())
                .thenReturn(extracted ? DocumentIndexingHandler.ID : DocumentExtractionHandler.ID);
        var result = service(true).read(Set.of(id)).get(id);
        assertThat(result.stage()).isEqualTo(stage);
        assertThat(result.state()).isEqualTo(DocumentProcessingService.State.QUEUED);
        assertThat(result.retry()).isEmpty();
    }

    @Test
    void unsupportedEventSchemaHasNoRetryControl() {
        when(rows.read(Set.of(id))).thenReturn(List.of(row("FAILED", 3)));
        when(handler.eventType()).thenReturn(new EventEnvelope.Type("PassagesPrepared", 2));
        assertThat(service(true).read(Set.of(id)).get(id).retry()).isEmpty();
    }

    @Test
    void pausedConsumersDoNotOfferRetryOrClaimSearchReadiness() {
        when(rows.read(Set.of(id))).thenReturn(List.of(row("PENDING", 1)));
        var result = service(false).read(Set.of(id)).get(id);
        assertThat(result.state()).isEqualTo(DocumentProcessingService.State.TEXT_AVAILABLE);
        assertThat(result.retry()).isEmpty();
        assertThat(service(false).retry(id, delivery, 2, UUID.randomUUID())).isFalse();
        verifyNoInteractions(retries);
    }

    @Test
    void onlyCurrentActiveGenerationIsReady() {
        var current = row("FAILED", 3);
        when(rows.read(Set.of(id)))
                .thenReturn(
                        List.of(
                                new JdbcProcessingRepository.Row(
                                        id,
                                        current.extraction(),
                                        Optional.empty(),
                                        1L,
                                        1L,
                                        20,
                                        3,
                                        3,
                                        null,
                                        null,
                                        null,
                                        0,
                                        0,
                                        0,
                                        Optional.empty(),
                                        null,
                                        0)));
        var ready = service(true).read(Set.of(id)).get(id);
        assertThat(ready.state()).isEqualTo(DocumentProcessingService.State.READY);
        assertThat(ready.retry()).isEmpty();
    }

    @Test
    void delegatesGuardedRetryAndRecordsSafeOutcome() {
        when(rows.read(Set.of(id))).thenReturn(List.of(row("FAILED", 3)));
        when(rows.lockVisible(id)).thenReturn(true);
        var event =
                new EventEnvelope(
                        UUID.randomUUID(),
                        new EventEnvelope.Type("PassagesPrepared", 1),
                        id,
                        1,
                        now,
                        UUID.randomUUID(),
                        null,
                        null,
                        Map.of());
        when(retries.retry(eq(delivery), eq(2), any(), any()))
                .thenAnswer(
                        call -> {
                            BiPredicate<String, EventEnvelope> guard = call.getArgument(3);
                            return guard.test(DocumentEmbeddingHandler.ID, event);
                        });
        assertThat(service(true).retry(id, delivery, 2, UUID.randomUUID())).isTrue();
        verify(rows, never()).clearExtractionFailure(any(), any());
        assertThat(
                        meters.get("secondbrain.document.processing.retries")
                                .tag("outcome", "requested")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void deletedOrChangedWorkCannotBeRetried(boolean visible) {
        when(rows.lockVisible(id)).thenReturn(visible);
        when(rows.read(Set.of(id))).thenReturn(List.of());
        var event =
                new EventEnvelope(
                        UUID.randomUUID(),
                        new EventEnvelope.Type("PassagesPrepared", 1),
                        id,
                        1,
                        now,
                        UUID.randomUUID(),
                        null,
                        null,
                        Map.of());
        when(retries.retry(any(), anyInt(), any(), any()))
                .thenAnswer(
                        call -> {
                            BiPredicate<String, EventEnvelope> guard = call.getArgument(3);
                            return guard.test(DocumentEmbeddingHandler.ID, event);
                        });
        assertThat(service(true).retry(id, delivery, 2, UUID.randomUUID())).isFalse();
    }

    @Test
    void rejectsOversizedOrInvalidRequestsBeforeIoAndReturnsImmutableViews() {
        var ids = new HashSet<UUID>();
        for (int n = 0; n < 51; n++) ids.add(UUID.randomUUID());
        assertThatThrownBy(() -> service(true).read(ids))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service(true).read(Set.of())).isEmpty();
        assertThatThrownBy(() -> service(true).retry(id, delivery, -1, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(rows, retries);
    }
}
