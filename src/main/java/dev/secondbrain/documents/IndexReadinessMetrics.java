package dev.secondbrain.documents;

import dev.secondbrain.embeddings.EmbeddingModel;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Committed state is durable; duration samples are optional process-local diagnostics. */
final class IndexReadinessMetrics {
    private final JdbcIndexingRepository indexes;
    private final EmbeddingModel model;
    private final MeterRegistry meters;
    private final Clock clock;
    private final TransactionTemplate reads;
    private Instant nextRead = Instant.MIN;
    private double cached = Double.NaN;

    IndexReadinessMetrics(
            JdbcIndexingRepository indexes,
            EmbeddingModel model,
            MeterRegistry meters,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        this.model = Objects.requireNonNull(model, "model");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.clock = Objects.requireNonNull(clock, "clock");
        reads = new TransactionTemplate(transactions);
        // Scrapes inside another transaction must never expose uncommitted activation.
        // After-commit callbacks also need their own connection, not the completed transaction.
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setReadOnly(true);
        reads.setTimeout(5);
        safely(
                () ->
                        Gauge.builder(
                                        "secondbrain.index.active",
                                        this,
                                        IndexReadinessMetrics::count)
                                .description(
                                        "Visible current-source active indexes compatible with the configured model")
                                .strongReference(true)
                                .register(meters));
        safely(
                () ->
                        Gauge.builder(
                                        "secondbrain.index.active.available",
                                        this,
                                        value -> Double.isNaN(value.count()) ? 0 : 1)
                                .strongReference(true)
                                .register(meters));
        safely(() -> meters.timer("secondbrain.index.ready.duration"));
    }

    void afterActivation(IndexingVersion version) {
        safely(
                () ->
                        TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                    @Override
                                    public void afterCommit() {
                                        safely(
                                                () -> {
                                                    var readyAt = clock.instant();
                                                    var accepted =
                                                            reads.execute(
                                                                    status ->
                                                                            indexes
                                                                                    .activeAcceptedAt(
                                                                                            version));
                                                    if (accepted == null
                                                            || accepted.isEmpty()
                                                            || accepted.orElseThrow()
                                                                    .isAfter(readyAt)) return;
                                                    meters.timer("secondbrain.index.ready.duration")
                                                            .record(
                                                                    Duration.between(
                                                                            accepted.orElseThrow(),
                                                                            readyAt));
                                                });
                                    }
                                }));
    }

    private synchronized double count() {
        var now = clock.instant();
        if (!now.isBefore(nextRead)) {
            try {
                var result = reads.execute(status -> indexes.activeCount(model));
                cached = result == null ? Double.NaN : result.doubleValue();
            } catch (RuntimeException unavailable) {
                cached = Double.NaN;
            }
            nextRead = now.plusSeconds(5);
        }
        return cached;
    }

    private static void safely(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException unavailable) {
            try {
                LoggerFactory.getLogger(IndexReadinessMetrics.class)
                        .atWarn()
                        .addKeyValue("code", "TELEMETRY_FAILED")
                        .log("Index readiness telemetry unavailable");
            } catch (RuntimeException loggingUnavailable) {
                // Telemetry cannot replace a successful publication result.
            }
        }
    }
}
