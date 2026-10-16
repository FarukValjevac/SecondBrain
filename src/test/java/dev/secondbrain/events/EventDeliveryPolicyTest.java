package dev.secondbrain.events;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class EventDeliveryPolicyTest {
    private static final EventEnvelope.Type TYPE = new EventEnvelope.Type("ProbeAccepted", 1);

    @Test
    void backoffUsesTheInjectedClockAndCapsExponentialDelayAndJitter() {
        var clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
        var settings = settings(Map.of());
        var maximum = new EventRetryPolicy(settings, clock, () -> 1.0);
        Assertions.assertThat(maximum.nextAttemptAt(1)).isEqualTo(clock.instant().plusSeconds(1));
        Assertions.assertThat(maximum.nextAttemptAt(3)).isEqualTo(clock.instant().plusSeconds(4));
        Assertions.assertThat(maximum.nextAttemptAt(10)).isEqualTo(clock.instant().plusSeconds(60));
        clock.advance(Duration.ofHours(1));
        var minimum = new EventRetryPolicy(settings, clock, () -> 0.0);
        Assertions.assertThat(minimum.nextAttemptAt(1)).isEqualTo(clock.instant().plusMillis(500));
        Assertions.assertThatThrownBy(() -> maximum.nextAttemptAt(0))
                .isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThatThrownBy(
                        () ->
                                new EventRetryPolicy(settings, clock, () -> Double.NaN)
                                        .nextAttemptAt(1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnboundedWorkersAttemptsAndInconsistentTiming() {
        for (var invalid :
                List.of(
                        Map.of("concurrency", "100"),
                        Map.of("claim-batch-size", "3"),
                        Map.of("max-attempts", "0"),
                        Map.of("execution-timeout", "1s"),
                        Map.of("lease-duration", "1s"),
                        Map.of("cleanup-batch-size", "1001"),
                        Map.of("completed-retention", "0d"),
                        Map.of("transaction-timeout", "1500ms"))) {
            Assertions.assertThatThrownBy(() -> settings(invalid))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void envelopeCopiesUuidReferencesAndRejectsUnboundedOrPrivateTraceData() {
        var references = new HashMap<String, UUID>();
        references.put("sourceId", UUID.randomUUID());
        var event = event(references, null);
        references.clear();
        Assertions.assertThat(event.references()).hasSize(1);
        Assertions.assertThatThrownBy(() -> event.references().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        Assertions.assertThatThrownBy(() -> event(Map.of("document text", UUID.randomUUID()), null))
                .isInstanceOf(IllegalArgumentException.class);
        var tooMany = new HashMap<String, UUID>();
        for (int index = 0; index < 17; index++) {
            tooMany.put("reference" + index, UUID.randomUUID());
        }
        Assertions.assertThatThrownBy(() -> event(tooMany, null))
                .isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThatThrownBy(() -> event(Map.of(), "sentinel-private-header"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("sentinel-private-header");
        Assertions.assertThatThrownBy(
                        () ->
                                event(
                                        Map.of(),
                                        "00-" + "0".repeat(32) + "-" + "1".repeat(16) + "-01"))
                .isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThat(
                        event(Map.of(), "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01")
                                .traceParent())
                .hasSize(55);
    }

    @Test
    void registryRejectsDuplicateHandlersAndRequiresExplicitKnownTypes() {
        EventHandler handler =
                new EventHandler() {
                    @Override
                    public String id() {
                        return "probe";
                    }

                    @Override
                    public EventEnvelope.Type eventType() {
                        return TYPE;
                    }

                    @Override
                    public Effect prepare(EventEnvelope event) {
                        return () -> Outcome.COMPLETED;
                    }
                };
        Assertions.assertThatThrownBy(() -> new EventRegistry(List.of(handler, handler), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var terminal = new EventEnvelope.Type("ProbeCompleted", 1);
        var registry = new EventRegistry(List.of(handler), List.of(terminal));
        Assertions.assertThat(registry.recipients(TYPE)).containsExactly("probe");
        Assertions.assertThat(registry.recipients(terminal)).isEmpty();
        Assertions.assertThatThrownBy(
                        () -> registry.recipients(new EventEnvelope.Type("Unknown", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static EventEnvelope event(Map<String, UUID> references, String trace) {
        return new EventEnvelope(
                UUID.randomUUID(),
                TYPE,
                UUID.randomUUID(),
                1,
                Instant.EPOCH,
                UUID.randomUUID(),
                null,
                trace,
                references);
    }

    private static EventDeliveryProperties settings(Map<String, String> overrides) {
        var values = new HashMap<String, Object>();
        values.put("secondbrain.events.concurrency", "2");
        overrides.forEach((key, value) -> values.put("secondbrain.events." + key, value));
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("secondbrain.events", Bindable.of(EventDeliveryProperties.class))
                .get();
    }
}
