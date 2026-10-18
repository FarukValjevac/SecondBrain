package dev.secondbrain.documents;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/** One bounded, private-data-free query per five seconds shared by the cleanup gauges. */
final class DocumentCleanupBacklogMetrics {
    private final JdbcDocumentRepository documents;
    private final Clock clock;
    private Instant nextRead = Instant.MIN;
    private JdbcDocumentRepository.CleanupBacklog cached;

    DocumentCleanupBacklogMetrics(
            JdbcDocumentRepository documents, MeterRegistry registry, Clock clock) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.clock = Objects.requireNonNull(clock, "clock");
        try {
            // The registry owns this collector for its lifetime, including outside a Spring
            // context.
            Gauge.builder(
                            "secondbrain.document.deletion.backlog",
                            this,
                            value -> value.value(JdbcDocumentRepository.CleanupBacklog::pending))
                    .tag("state", "pending")
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(
                            "secondbrain.document.deletion.backlog",
                            this,
                            value -> value.value(JdbcDocumentRepository.CleanupBacklog::failed))
                    .tag("state", "failed")
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(
                            "secondbrain.document.deletion.backlog.oldest",
                            this,
                            value -> value.value(JdbcDocumentRepository.CleanupBacklog::age))
                    .baseUnit("seconds")
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(
                            "secondbrain.document.deletion.backlog.available",
                            this,
                            value -> value.snapshot() == null ? 0 : 1)
                    .strongReference(true)
                    .register(registry);
        } catch (RuntimeException unavailable) {
            try {
                LoggerFactory.getLogger(getClass())
                        .atWarn()
                        .addKeyValue("code", "TELEMETRY_FAILED")
                        .log("Cleanup backlog telemetry unavailable");
            } catch (RuntimeException loggingUnavailable) {
                // A broken telemetry backend cannot prevent deletion or startup.
            }
        }
    }

    private double value(ToDoubleFunction<JdbcDocumentRepository.CleanupBacklog> read) {
        var current = snapshot();
        return current == null ? Double.NaN : read.applyAsDouble(current);
    }

    private synchronized JdbcDocumentRepository.CleanupBacklog snapshot() {
        if (!clock.instant().isBefore(nextRead)) {
            try {
                cached = documents.cleanupBacklog(clock.instant());
            } catch (RuntimeException unavailable) {
                cached = null;
            } finally {
                // Cache failures too, avoiding one timeout per gauge in the same scrape.
                nextRead = clock.instant().plusSeconds(5);
            }
        }
        return cached;
    }
}
