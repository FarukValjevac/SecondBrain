package dev.secondbrain.events;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class EventDispatcherTest {
    private final JdbcEventStore store = Mockito.mock(JdbcEventStore.class);
    private final EventHandler handler = Mockito.mock(EventHandler.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(EventDispatcher.class);
    private final ListAppender<ILoggingEvent> logs =
            new ListAppender<>() {
                @Override
                protected void append(ILoggingEvent event) {
                    event.prepareForDeferredProcessing();
                    super.append(event);
                }
            };
    private Map<String, String> previous;
    private boolean previouslyInterrupted;

    @BeforeEach
    void captureLogs() {
        previous = MDC.getCopyOfContextMap();
        previouslyInterrupted = Thread.interrupted();
        MDC.clear();
        logs.start();
        logger.addAppender(logs);
        Mockito.when(handler.id()).thenReturn("probe");
        Mockito.when(handler.eventType()).thenReturn(new EventEnvelope.Type("Probe", 1));
    }

    @AfterEach
    void restoreContext() {
        logger.detachAppender(logs);
        logs.stop();
        MDC.setContextMap(previous);
        Thread.interrupted();
        if (previouslyInterrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void logsAcknowledgmentOnlyWhenTheStoreAcceptsIt(boolean committed) throws Exception {
        var lease = lease();
        EventHandler.Effect effect = () -> EventHandler.Outcome.COMPLETED;
        Mockito.when(
                        handler.prepare(
                                Mockito.eq(lease.event()),
                                Mockito.any(EventHandler.Checkpoint.class)))
                .thenReturn(effect);
        Mockito.when(store.complete(lease, effect)).thenReturn(committed);

        dispatch(lease, List.of(handler));

        var fields = fields(lease);
        Assertions.assertThat(fields)
                .containsEntry("operation", "event_delivery")
                .containsEntry("outcome", committed ? "acknowledged" : "lease_lost");
        Mockito.verify(store).complete(lease, effect);
    }

    @Test
    void reportsLostOwnershipWhenFailureCannotBeRecorded() {
        var lease = lease();
        dispatch(lease, List.of());
        Assertions.assertThat(fields(lease))
                .containsEntry("outcome", "lease_lost")
                .containsEntry("code", "HANDLER_MISSING");
        Mockito.verify(store).fail(lease, DeliveryFailure.Code.HANDLER_MISSING, false);
    }

    @Test
    void tracingFailureDoesNotPreventDeliveryCompletion() throws Exception {
        var lease = lease();
        EventHandler.Effect effect = () -> EventHandler.Outcome.COMPLETED;
        Mockito.when(
                        handler.prepare(
                                Mockito.eq(lease.event()),
                                Mockito.any(EventHandler.Checkpoint.class)))
                .thenReturn(effect);
        Mockito.when(store.complete(lease, effect)).thenReturn(true);
        var tracer = Mockito.mock(io.micrometer.tracing.Tracer.class);
        Mockito.when(tracer.spanBuilder()).thenThrow(new IllegalStateException("sentinel-private"));
        var dispatcher =
                new EventDispatcher(
                        store,
                        new EventRegistry(List.of(handler), List.of()),
                        new EventTracing(tracer));
        var context = new DeliveryLogContext(lease);
        try (context) {
            Assertions.assertThatCode(() -> dispatcher.dispatch(lease)).doesNotThrowAnyException();
        }
        Mockito.verify(store).complete(lease, effect);
        Assertions.assertThat(fields(lease)).containsEntry("outcome", "acknowledged");
    }

    @ParameterizedTest
    @EnumSource(
            value = DeliveryFailure.Code.class,
            names = {
                "HANDLER_MISSING", "EVENT_UNSUPPORTED", "SCHEMA_UNSUPPORTED",
                "HANDLER_PERMANENT", "HANDLER_TRANSIENT", "TIMED_OUT"
            })
    void logsOnlySafeFailureCodesAndPreservesInterruption(DeliveryFailure.Code code)
            throws Exception {
        var lease = lease();
        switch (code) {
            case EVENT_UNSUPPORTED ->
                    Mockito.when(handler.eventType())
                            .thenReturn(new EventEnvelope.Type("Other", 1));
            case SCHEMA_UNSUPPORTED ->
                    Mockito.when(handler.eventType())
                            .thenReturn(new EventEnvelope.Type("Probe", 2));
            case HANDLER_PERMANENT ->
                    Mockito.when(
                                    handler.prepare(
                                            Mockito.eq(lease.event()),
                                            Mockito.any(EventHandler.Checkpoint.class)))
                            .thenThrow(
                                    new DeliveryFailure(
                                            new IllegalStateException("sentinel-private")));
            case HANDLER_TRANSIENT ->
                    Mockito.when(
                                    handler.prepare(
                                            Mockito.eq(lease.event()),
                                            Mockito.any(EventHandler.Checkpoint.class)))
                            .thenThrow(new IllegalStateException("sentinel-private"));
            case TIMED_OUT ->
                    Mockito.when(
                                    handler.prepare(
                                            Mockito.eq(lease.event()),
                                            Mockito.any(EventHandler.Checkpoint.class)))
                            .thenThrow(new InterruptedException("sentinel-private"));
            default -> {}
        }
        boolean retryable =
                code == DeliveryFailure.Code.HANDLER_TRANSIENT
                        || code == DeliveryFailure.Code.TIMED_OUT;
        Mockito.when(store.fail(lease, code, retryable)).thenReturn(true);

        dispatch(
                lease, code == DeliveryFailure.Code.HANDLER_MISSING ? List.of() : List.of(handler));

        Assertions.assertThat(fields(lease))
                .containsEntry("outcome", "failure_recorded")
                .containsEntry("code", code.name());
        Mockito.verify(store).fail(lease, code, retryable);
        Assertions.assertThat(Thread.currentThread().isInterrupted())
                .isEqualTo(code == DeliveryFailure.Code.TIMED_OUT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void classifiesDomainFailuresAndPassesTheirEffectsToTheFencedStore(boolean retryable)
            throws Exception {
        var lease = lease();
        EventHandler.Effect effect = () -> EventHandler.Outcome.COMPLETED;
        var code =
                retryable
                        ? DeliveryFailure.Code.HANDLER_TRANSIENT
                        : DeliveryFailure.Code.HANDLER_PERMANENT;
        Mockito.when(
                        handler.prepare(
                                Mockito.eq(lease.event()),
                                Mockito.any(EventHandler.Checkpoint.class)))
                .thenThrow(
                        new DeliveryFailure(
                                new IllegalStateException("private-document-source"),
                                retryable,
                                effect));
        Mockito.when(store.fail(lease, code, retryable, effect)).thenReturn(true);
        dispatch(lease, List.of(handler));
        Mockito.verify(store).fail(lease, code, retryable, effect);
        Assertions.assertThat(fields(lease))
                .containsEntry("code", code.name())
                .containsEntry("outcome", "failure_recorded");
        Assertions.assertThat(logs.list.getFirst().getFormattedMessage())
                .doesNotContain("private-document-source");
    }

    @Test
    void suppliesAnOwnershipBoundCheckpointWithoutAcknowledgingProgress() throws Exception {
        var lease = lease();
        EventHandler.Effect progress = () -> EventHandler.Outcome.COMPLETED;
        EventHandler.Effect finish = () -> EventHandler.Outcome.COMPLETED;
        Mockito.when(store.checkpoint(lease, progress))
                .thenReturn(java.util.Optional.of(EventHandler.Outcome.COMPLETED));
        Mockito.when(
                        handler.prepare(
                                Mockito.eq(lease.event()),
                                Mockito.any(EventHandler.Checkpoint.class)))
                .thenAnswer(
                        call -> {
                            EventHandler.Checkpoint checkpoint = call.getArgument(1);
                            Assertions.assertThat(checkpoint.save(progress))
                                    .contains(EventHandler.Outcome.COMPLETED);
                            Mockito.verify(store, Mockito.never())
                                    .complete(Mockito.any(), Mockito.any());
                            return finish;
                        });
        dispatch(lease, List.of(handler));
        Mockito.verify(store).checkpoint(lease, progress);
        Mockito.verify(store).complete(lease, finish);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void passesRetryHintsOnlyForRetryableFailures(boolean retryable) throws Exception {
        var lease = lease();
        var retryAt = Instant.parse("2030-01-02T00:00:00Z");
        Mockito.when(
                        handler.prepare(
                                Mockito.eq(lease.event()),
                                Mockito.any(EventHandler.Checkpoint.class)))
                .thenThrow(
                        new DeliveryFailure(
                                new IllegalStateException("private-provider-detail"),
                                retryable,
                                retryAt));
        dispatch(lease, List.of(handler));
        if (retryable)
            Mockito.verify(store)
                    .fail(lease, DeliveryFailure.Code.HANDLER_TRANSIENT, true, retryAt);
        else Mockito.verify(store).fail(lease, DeliveryFailure.Code.HANDLER_PERMANENT, false);
        Assertions.assertThat(logs.list.getFirst().getFormattedMessage())
                .doesNotContain("private-provider-detail");
    }

    private void dispatch(DeliveryLease lease, List<EventHandler> handlers) {
        var dispatcher =
                new EventDispatcher(
                        store,
                        new EventRegistry(handlers, List.of()),
                        new EventTracing(io.micrometer.tracing.Tracer.NOOP));
        var context = new DeliveryLogContext(lease);
        try (context) {
            dispatcher.dispatch(lease);
        }
        Assertions.assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private Map<String, Object> fields(DeliveryLease lease) {
        Assertions.assertThat(logs.list).hasSize(1);
        var event = logs.list.getFirst();
        Assertions.assertThat(event.getThrowableProxy()).isNull();
        Assertions.assertThat(event.getMDCPropertyMap())
                .containsEntry("correlation_id", lease.event().correlationId().toString())
                .containsEntry("event_id", lease.event().eventId().toString())
                .containsEntry("delivery_id", lease.deliveryId().toString())
                .containsEntry("attempt", "1");
        Assertions.assertThat(event.getFormattedMessage() + event.getKeyValuePairs())
                .doesNotContain("sentinel-private");
        var fields = new java.util.HashMap<String, Object>();
        event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
        return fields;
    }

    private DeliveryLease lease() {
        var event =
                new EventEnvelope(
                        UUID.randomUUID(),
                        new EventEnvelope.Type("Probe", 1),
                        UUID.randomUUID(),
                        1,
                        Instant.EPOCH,
                        UUID.randomUUID(),
                        null,
                        null,
                        Map.of());
        return new DeliveryLease(
                UUID.randomUUID(),
                "probe",
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                Instant.MAX,
                event);
    }
}
