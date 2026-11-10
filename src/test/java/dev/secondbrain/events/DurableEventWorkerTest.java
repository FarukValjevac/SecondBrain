package dev.secondbrain.events;

import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class DurableEventWorkerTest {
    @Test
    void timeoutsInterruptWorkButDoNotReleaseItsConcurrencySlotBeforeTheThreadStops()
            throws Exception {
        var clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
        var settings =
                new EventDeliveryProperties(
                        1,
                        1,
                        Duration.ofMillis(50),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(4),
                        Duration.ofSeconds(1),
                        3,
                        Duration.ofMillis(100),
                        Duration.ofSeconds(1),
                        Duration.ofDays(30),
                        1,
                        Duration.ofHours(1));
        var event =
                new EventEnvelope(
                        UUID.randomUUID(),
                        new EventEnvelope.Type("Probe", 1),
                        UUID.randomUUID(),
                        1,
                        clock.instant(),
                        UUID.randomUUID(),
                        null,
                        null,
                        Map.of());
        var lease =
                new DeliveryLease(
                        UUID.randomUUID(),
                        "probe",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        clock.instant().plusSeconds(4),
                        event);
        var store = Mockito.mock(JdbcEventStore.class);
        Mockito.when(store.cleanup())
                .thenThrow(new IllegalStateException("Maintenance unavailable"));
        var dispatcher = Mockito.mock(EventDispatcher.class);
        var claims = new AtomicInteger();
        Mockito.when(store.claim(ArgumentMatchers.any()))
                .thenAnswer(
                        invocation -> {
                            claims.incrementAndGet();
                            return java.util.Optional.of(lease);
                        });
        Mockito.when(store.renew(lease)).thenReturn(true);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Mockito.doAnswer(
                        invocation -> {
                            Assertions.assertThat(MDC.get("correlation_id"))
                                    .isEqualTo(event.correlationId().toString());
                            Assertions.assertThat(MDC.get("delivery_id"))
                                    .isEqualTo(lease.deliveryId().toString());
                            Assertions.assertThat(MDC.get("attempt")).isEqualTo("1");
                            entered.countDown();
                            boolean restore = false;
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                            try {
                                while (release.getCount() != 0) {
                                    long remaining = deadline - System.nanoTime();
                                    if (remaining <= 0) {
                                        throw new IllegalStateException("Test wait expired");
                                    }
                                    try {
                                        release.await(remaining, TimeUnit.NANOSECONDS);
                                    } catch (InterruptedException expected) {
                                        restore = true;
                                        interrupted.countDown();
                                    }
                                }
                            } finally {
                                if (restore) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            return null;
                        })
                .when(dispatcher)
                .dispatch(lease);
        var worker = new DurableEventWorker(store, dispatcher, settings, clock);
        worker.start();
        Assertions.assertThat(worker.isOperational()).isTrue();
        try {
            Assertions.assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            clock.advance(Duration.ofSeconds(5));
            Assertions.assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () ->
                                    Mockito.verify(store)
                                            .fail(lease, DeliveryFailure.Code.TIMED_OUT, true));
            Assertions.assertThat(claims).hasValue(1);
            Mockito.verify(dispatcher).dispatch(lease);
        } finally {
            release.countDown();
            worker.stop();
            Assertions.assertThat(worker.isOperational()).isFalse();
        }
    }

    @Test
    void shutdownCannotReplaceAnExecutorWhileAnUncooperativeHandlerIsStillAlive() throws Exception {
        var clock = new MutableClock(Instant.parse("2030-01-01T00:00:00Z"));
        var settings =
                new EventDeliveryProperties(
                        1,
                        1,
                        Duration.ofMillis(50),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1),
                        3,
                        Duration.ofMillis(100),
                        Duration.ofSeconds(1),
                        Duration.ofDays(30),
                        100,
                        Duration.ofHours(1));
        var event =
                new EventEnvelope(
                        UUID.randomUUID(),
                        new EventEnvelope.Type("ShutdownProbe", 1),
                        UUID.randomUUID(),
                        1,
                        clock.instant(),
                        UUID.randomUUID(),
                        null,
                        null,
                        java.util.Map.of());
        var lease =
                new DeliveryLease(
                        UUID.randomUUID(),
                        "shutdown-probe",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        clock.instant().plusSeconds(5),
                        event);
        var store = Mockito.mock(JdbcEventStore.class);
        var dispatcher = Mockito.mock(EventDispatcher.class);
        var claims = new AtomicInteger();
        Mockito.when(store.claim(ArgumentMatchers.any()))
                .thenAnswer(
                        call ->
                                claims.getAndIncrement() == 0
                                        ? java.util.Optional.of(lease)
                                        : java.util.Optional.empty());
        Mockito.when(store.renew(lease)).thenReturn(true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        Mockito.doAnswer(
                        call -> {
                            entered.countDown();
                            boolean restore = false;
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                            try {
                                while (release.getCount() != 0) {
                                    long remaining = deadline - System.nanoTime();
                                    if (remaining <= 0)
                                        throw new IllegalStateException("Test wait expired");
                                    try {
                                        release.await(remaining, TimeUnit.NANOSECONDS);
                                    } catch (InterruptedException expected) {
                                        restore = true;
                                        interrupted.countDown();
                                    }
                                }
                            } finally {
                                if (restore) Thread.currentThread().interrupt();
                            }
                            return null;
                        })
                .when(dispatcher)
                .dispatch(lease);
        var worker = new DurableEventWorker(store, dispatcher, settings, clock);
        try {
            worker.start();
            Assertions.assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            worker.stop();
            Assertions.assertThat(interrupted.getCount()).isZero();
            Assertions.assertThat(worker.activeCount()).isEqualTo(1);
            Assertions.assertThatThrownBy(worker::start).isInstanceOf(IllegalStateException.class);
            release.countDown();
            Awaitility.await()
                    .atMost(Duration.ofSeconds(3))
                    .untilAsserted(
                            () ->
                                    Assertions.assertThatCode(worker::start)
                                            .doesNotThrowAnyException());
            Assertions.assertThat(worker.isOperational()).isTrue();
        } finally {
            release.countDown();
            worker.stop();
        }
    }
}
