package dev.secondbrain.documents;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("secondbrain.documents.cleanup")
record OriginalCleanupProperties(
        @DefaultValue("1h") Duration gracePeriod,
        @DefaultValue("1m") Duration interval,
        @DefaultValue("100") int batchSize) {
    OriginalCleanupProperties {
        requireRange(gracePeriod, Duration.ofMinutes(1), Duration.ofDays(7));
        requireRange(interval, Duration.ofSeconds(1), Duration.ofHours(1));
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Original cleanup batch size out of range");
        }
    }

    private static void requireRange(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Original cleanup timing out of range");
        }
    }
}
