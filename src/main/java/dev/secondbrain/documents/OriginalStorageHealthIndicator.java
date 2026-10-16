package dev.secondbrain.documents;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component("originalStorageHealthIndicator")
final class OriginalStorageHealthIndicator implements HealthIndicator {
    private final OriginalFileStore originals;
    private final boolean required;

    OriginalStorageHealthIndicator(
            OriginalFileStore originals,
            @Value("${secondbrain.events.enabled:true}") boolean uploadsEnabled) {
        this.originals = originals;
        this.required = uploadsEnabled;
    }

    @Override
    public Health health() {
        if (!required) {
            return Health.up().build();
        }
        try {
            originals.checkReadiness();
            return Health.up().build();
        } catch (IOException | RuntimeException unavailable) {
            // Do not attach exceptions: provider messages can contain private paths.
            return Health.down().build();
        }
    }
}
