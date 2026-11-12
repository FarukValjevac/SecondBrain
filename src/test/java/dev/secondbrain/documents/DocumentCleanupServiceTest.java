package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

class DocumentCleanupServiceTest {
    private final JdbcDocumentRepository documents = mock(JdbcDocumentRepository.class);
    private final EventRetryService retries = mock(EventRetryService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final EventHandler handler = mock(EventHandler.class);
    private final UUID id = UUID.randomUUID();
    private final UUID delivery = UUID.randomUUID();
    private final Instant now = Instant.parse("2030-01-01T01:00:00Z");
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void setup() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(handler.id()).thenReturn(DocumentDeletionHandler.ID);
        when(handler.eventType()).thenReturn(DocumentDeletionRequestedEvent.TYPE);
    }

    private DocumentCleanupService service(boolean polling, boolean configured) {
        return new DocumentCleanupService(
                documents,
                retries,
                () -> configured ? List.of(handler) : List.of(),
                polling,
                transactions,
                Clock.fixed(now, ZoneOffset.UTC),
                new DocumentTelemetry(meters, ObservationRegistry.NOOP));
    }

    @ParameterizedTest
    @CsvSource({"PENDING,PENDING,false", "RUNNING,RUNNING,false", "FAILED,FAILED,true"})
    void presentsPersistedStatusAndAgeWithoutContent(
            String stored, DocumentCleanupService.State state, boolean retry) {
        when(documents.cleanupRequests(0))
                .thenReturn(
                        List.of(
                                new JdbcDocumentRepository.CleanupRow(
                                        id, now.minusSeconds(60), delivery, stored, 5, 2, 1)));
        var row = service(true, true).read(0).requests().getFirst();
        assertThat(row.state()).isEqualTo(state);
        assertThat(row.ageSeconds()).isEqualTo(60);
        assertThat(row.attempts()).isEqualTo(5);
        assertThat(row.retry().isPresent()).isEqualTo(retry);
        assertThat(row.toString()).doesNotContain(id.toString(), delivery.toString());
    }

    @ParameterizedTest
    @CsvSource({"false,true", "true,false"})
    void pausedCleanupOffersNoRetry(boolean polling, boolean configured) {
        when(documents.cleanupRequests(0))
                .thenReturn(
                        List.of(
                                new JdbcDocumentRepository.CleanupRow(
                                        id, now.plusSeconds(5), delivery, "FAILED", 5, 2, 1)));
        var page = service(polling, configured).read(0);
        assertThat(page.enabled()).isFalse();
        assertThat(page.requests().getFirst().retry()).isEmpty();
        assertThat(page.requests().getFirst().ageSeconds()).isZero();
        assertThat(service(polling, configured).retry(id, delivery, 2, UUID.randomUUID()))
                .isFalse();
        verifyNoInteractions(retries);
    }

    @Test
    void pagesAreBoundedImmutableAndHandleUnscheduledRequests() {
        var rows = new ArrayList<JdbcDocumentRepository.CleanupRow>();
        for (int i = 0; i < 51; i++)
            rows.add(
                    new JdbcDocumentRepository.CleanupRow(
                            UUID.randomUUID(), now, null, null, 0, 0, 0));
        when(documents.cleanupRequests(2)).thenReturn(rows);
        var page = service(true, true).read(2);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.requests()).hasSize(50).allMatch(row -> row.retry().isEmpty());
        assertThatThrownBy(() -> page.requests().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> service(true, true).read(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedSchemasAndExhaustedCountersOfferNoRetry() {
        when(documents.cleanupRequests(0))
                .thenReturn(
                        List.of(
                                new JdbcDocumentRepository.CleanupRow(
                                        id, now, delivery, "FAILED", 5, 2, 2),
                                new JdbcDocumentRepository.CleanupRow(
                                        id, now, delivery, "FAILED", Integer.MAX_VALUE, 2, 1),
                                new JdbcDocumentRepository.CleanupRow(
                                        id, now, delivery, "FAILED", 5, Integer.MAX_VALUE, 1)));
        assertThat(service(true, true).read(0).requests()).allMatch(row -> row.retry().isEmpty());
    }

    @Test
    void guardRequiresTheMatchingCurrentTombstoneAndDeletionContract() {
        when(retries.retry(eq(delivery), eq(2), any(), any()))
                .thenAnswer(
                        call -> {
                            BiPredicate<String, EventEnvelope> guard = call.getArgument(3);
                            var event =
                                    new EventEnvelope(
                                            UUID.randomUUID(),
                                            DocumentDeletionRequestedEvent.TYPE,
                                            id,
                                            4,
                                            now,
                                            UUID.randomUUID(),
                                            null,
                                            null,
                                            Map.of());
                            assertThat(guard.test(DocumentExtractionHandler.ID, event)).isFalse();
                            var foreign =
                                    new EventEnvelope(
                                            event.eventId(),
                                            event.type(),
                                            UUID.randomUUID(),
                                            4,
                                            now,
                                            event.correlationId(),
                                            null,
                                            null,
                                            Map.of());
                            assertThat(guard.test(DocumentDeletionHandler.ID, foreign)).isFalse();
                            var unsupported =
                                    new EventEnvelope(
                                            event.eventId(),
                                            new EventEnvelope.Type(event.type().name(), 2),
                                            id,
                                            4,
                                            now,
                                            event.correlationId(),
                                            null,
                                            null,
                                            Map.of());
                            assertThat(guard.test(DocumentDeletionHandler.ID, unsupported))
                                    .isFalse();
                            verify(documents, never()).lockDeletion(any(), anyLong());
                            assertThat(guard.test(DocumentDeletionHandler.ID, event)).isFalse();
                            when(documents.lockDeletion(id, 4)).thenReturn(true);
                            return guard.test(DocumentDeletionHandler.ID, event);
                        });
        assertThat(service(true, true).retry(id, delivery, 2, UUID.randomUUID())).isTrue();
        assertThat(
                        meters.get("secondbrain.document.deletion.retries")
                                .tag("outcome", "requested")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void rejectsInvalidCommandsAndReportsUnavailableWithoutChangingFailures() {
        assertThatThrownBy(() -> service(true, true).retry(id, delivery, -1, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        when(retries.retry(any(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("private-db-sentinel"));
        assertThatThrownBy(() -> service(true, true).retry(id, delivery, 0, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("private-db-sentinel");
        assertThat(
                        meters.get("secondbrain.document.deletion.retries")
                                .tag("outcome", "unavailable")
                                .counter()
                                .count())
                .isEqualTo(1);
    }
}
