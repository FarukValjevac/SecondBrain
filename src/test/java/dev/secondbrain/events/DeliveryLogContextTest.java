package dev.secondbrain.events;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

class DeliveryLogContextTest {
    private Map<String, String> previous;

    @BeforeEach
    void isolateContext() {
        previous = MDC.getCopyOfContextMap();
        MDC.clear();
    }

    @AfterEach
    void restoreContext() {
        MDC.setContextMap(previous);
    }

    @Test
    void usesOnlyPersistedIdsAndRestoresTheOuterScope() {
        var lease = lease(UUID.randomUUID());
        MDC.put("unrelated", "sentinel-private-outer-value");
        var context = new DeliveryLogContext(lease);
        try (context) {
            Assertions.assertThat(MDC.getCopyOfContextMap())
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of(
                                    "correlation_id", lease.event().correlationId().toString(),
                                    "event_id", lease.event().eventId().toString(),
                                    "document_id", lease.event().documentId().toString(),
                                    "delivery_id", lease.deliveryId().toString(),
                                    "processing_version", "2",
                                    "attempt", "3",
                                    "causation_id", lease.event().causationId().toString()));
        }
        Assertions.assertThat(MDC.getCopyOfContextMap())
                .containsExactlyEntriesOf(Map.of("unrelated", "sentinel-private-outer-value"));
    }

    @Test
    void doesNotCarryCausationBetweenDeliveriesOnTheSameThread() {
        var first = new DeliveryLogContext(lease(UUID.randomUUID()));
        try (first) {
            Assertions.assertThat(MDC.get("causation_id")).isNotNull();
        }
        var second = new DeliveryLogContext(lease(null));
        try (second) {
            Assertions.assertThat(MDC.get("causation_id")).isNull();
        }
        Assertions.assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void discardsHandlerContextEvenWhenTheHandlerThrows() {
        Assertions.assertThatThrownBy(
                        () -> {
                            var context = new DeliveryLogContext(lease(null));
                            try (context) {
                                MDC.put("handler_value", "sentinel-private-value");
                                throw new IllegalStateException("sentinel-private-error");
                            }
                        })
                .isInstanceOf(IllegalStateException.class);
        Assertions.assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private DeliveryLease lease(UUID causationId) {
        var event =
                new EventEnvelope(
                        UUID.randomUUID(),
                        new EventEnvelope.Type("Probe", 1),
                        UUID.randomUUID(),
                        2,
                        Instant.EPOCH,
                        UUID.randomUUID(),
                        causationId,
                        null,
                        Map.of());
        return new DeliveryLease(
                UUID.randomUUID(),
                "probe",
                UUID.randomUUID(),
                UUID.randomUUID(),
                3,
                Instant.MAX,
                event);
    }
}
