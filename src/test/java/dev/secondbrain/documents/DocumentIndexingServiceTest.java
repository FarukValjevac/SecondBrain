package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventPublisher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

class DocumentIndexingServiceTest {
    private final JdbcIndexingRepository indexes = mock(JdbcIndexingRepository.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final UUID document = UUID.randomUUID();
    private final UUID correlation = UUID.randomUUID();
    private final DocumentIndexingService service =
            new DocumentIndexingService(
                    indexes,
                    publisher,
                    new CallbackTransactions(),
                    Clock.fixed(now, ZoneOffset.UTC),
                    new DocumentTelemetry(meters, ObservationRegistry.NOOP));

    @AfterEach
    void closeMeters() {
        meters.close();
    }

    @Test
    void publishesAnIndexVersionWithoutConfusingItWithItsSourceVersion() {
        var eventId = UUID.randomUUID();
        when(indexes.request(document, OptionalLong.of(7), now))
                .thenReturn(Optional.of(new IndexingVersion(document, 7, 2, now)));
        when(publisher.publish(any())).thenReturn(eventId);
        var receipt = service.request(document, 7, correlation).orElseThrow();
        assertThat(receipt).isEqualTo(new DocumentIndexingService.Receipt(document, 7, 2, eventId));
        var event = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo(ReindexRequestedEvent.TYPE);
        assertThat(event.getValue().processingVersion()).isEqualTo(7);
        assertThat(event.getValue().correlationId()).isEqualTo(correlation);
        assertThat(event.getValue().references()).isEmpty();
        assertThat(outcome("requested")).isEqualTo(1);
        assertThat(meters.getMeters())
                .allSatisfy(
                        meter ->
                                assertThat(meter.getId().getTags().toString())
                                        .doesNotContain(
                                                document.toString(), correlation.toString()));
    }

    @Test
    void unavailablePrerequisitesDoNotPublishAnEvent() {
        when(indexes.request(document, OptionalLong.empty(), now)).thenReturn(Optional.empty());
        assertThat(service.requestNext(document, correlation)).isEmpty();
        verifyNoInteractions(publisher);
        assertThat(outcome("unchanged")).isEqualTo(1);
    }

    @Test
    void publicationFailurePropagatesAndDoesNotReportCommittedSuccess() {
        when(indexes.request(document, OptionalLong.of(1), now))
                .thenReturn(Optional.of(new IndexingVersion(document, 1, 1, now)));
        when(publisher.publish(any())).thenThrow(new IllegalStateException("private-fault"));
        assertThatThrownBy(() -> service.request(document, 1, correlation))
                .isInstanceOf(IllegalStateException.class);
        assertThat(outcome("unavailable")).isEqualTo(1);
        assertThat(
                        meters.find("secondbrain.document.indexing.requests")
                                .tag("outcome", "requested")
                                .counter())
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidVersionsBeforeAnyPersistenceOrPublication(long version) {
        assertThatThrownBy(() -> service.request(document, version, correlation))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(indexes, publisher);
    }

    private double outcome(String value) {
        return meters.get("secondbrain.document.indexing.requests")
                .tag("outcome", value)
                .counter()
                .count();
    }

    /** Exercises Spring callbacks only; PostgreSQL atomicity belongs to the integration suite. */
    static final class CallbackTransactions extends AbstractPlatformTransactionManager {
        @java.io.Serial private static final long serialVersionUID = 1L;

        @Override
        protected Object doGetTransaction() {
            return org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return Boolean.TRUE.equals(transaction);
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
