package dev.secondbrain.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("secondbrain.events")
record EventDeliveryProperties(
        @DefaultValue("2") int concurrency,
        @DefaultValue("2") int claimBatchSize,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("30s") Duration leaseDuration,
        @DefaultValue("2m") Duration executionTimeout,
        @DefaultValue("5s") Duration transactionTimeout,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("1m") Duration maxBackoff,
        @DefaultValue("30d") Duration completedRetention,
        @DefaultValue("100") int cleanupBatchSize,
        @DefaultValue("1h") Duration cleanupInterval) {

    EventDeliveryProperties {
        if (concurrency < 1
                || concurrency > 4
                || claimBatchSize < 1
                || claimBatchSize > concurrency
                || maxAttempts < 1
                || maxAttempts > 10
                || cleanupBatchSize < 1
                || cleanupBatchSize > 1000) {
            throw new IllegalArgumentException(
                    "Event concurrency, batch or attempt limits out of range");
        }
        requireRange(pollInterval, Duration.ofMillis(50), Duration.ofSeconds(30));
        requireRange(leaseDuration, pollInterval.multipliedBy(3), Duration.ofMinutes(5));
        requireRange(executionTimeout, leaseDuration, Duration.ofMinutes(15));
        requireRange(transactionTimeout, Duration.ofSeconds(1), leaseDuration.dividedBy(2));
        if (transactionTimeout.getNano() != 0) {
            throw new IllegalArgumentException("Transaction timeout must use whole seconds");
        }
        requireRange(initialBackoff, Duration.ofMillis(100), Duration.ofMinutes(5));
        requireRange(maxBackoff, initialBackoff, Duration.ofHours(1));
        requireRange(completedRetention, Duration.ofDays(1), Duration.ofDays(365));
        requireRange(cleanupInterval, Duration.ofMinutes(1), Duration.ofDays(1));
    }

    private static void requireRange(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Event timing limit out of range");
        }
    }
}
