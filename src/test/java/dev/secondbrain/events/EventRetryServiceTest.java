package dev.secondbrain.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

class EventRetryServiceTest {
    private final JdbcEventStore store = mock(JdbcEventStore.class);
    private final EventHandler handler = mock(EventHandler.class);
    private final EventEnvelope.Type type = new EventEnvelope.Type("ProcessingProbe", 1);

    @ParameterizedTest
    @CsvSource({"true,1,true", "false,1,false", "true,2,false"})
    void onlyRegisteredCompatibleHandlersCanRunTheOwnersGuard(
            boolean registered, int schema, boolean expected) {
        when(handler.id()).thenReturn("probe.process.v1");
        when(handler.eventType()).thenReturn(type);
        var registry = new EventRegistry(registered ? List.of(handler) : List.of(), List.of(type));
        var service = new EventRetryService(store, () -> registry);
        var id = UUID.randomUUID();
        var calls = new AtomicInteger();
        var event =
                new EventEnvelope(
                        UUID.randomUUID(),
                        new EventEnvelope.Type(type.name(), schema),
                        UUID.randomUUID(),
                        1,
                        Instant.EPOCH,
                        UUID.randomUUID(),
                        null,
                        null,
                        Map.of());
        when(store.retryFailed(eq(id), eq(0), any(), any()))
                .thenAnswer(
                        invocation -> {
                            BiPredicate<String, EventEnvelope> guard = invocation.getArgument(3);
                            return guard.test("probe.process.v1", event);
                        });
        assertThat(
                        service.retry(
                                id,
                                0,
                                UUID.randomUUID(),
                                (h, e) -> {
                                    calls.incrementAndGet();
                                    return true;
                                }))
                .isEqualTo(expected);
        assertThat(calls).hasValue(expected ? 1 : 0);
    }

    @Test
    void rejectsInvalidCommandsBeforePersistence() {
        var service =
                new EventRetryService(
                        store,
                        () -> {
                            throw new AssertionError("Registry accessed");
                        });
        assertThatThrownBy(
                        () ->
                                service.retry(
                                        UUID.randomUUID(), -1, UUID.randomUUID(), (h, e) -> true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.retry(null, 0, UUID.randomUUID(), (h, e) -> true))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(store);
    }
}
