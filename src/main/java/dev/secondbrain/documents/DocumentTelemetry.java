package dev.secondbrain.documents;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.UUID;

/** Fixed metadata only. Telemetry is best effort and never receives a private exception. */
final class DocumentTelemetry {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentTelemetry.class);
    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    DocumentTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
        for (var outcome :
                new Outcome[] {Outcome.ACCEPTED, Outcome.REJECTED, Outcome.UNAVAILABLE}) {
            safely(() -> meters.counter("secondbrain.document.uploads", "outcome", outcome.tag()));
        }
        for (var outcome : new Outcome[] {Outcome.COMPLETED, Outcome.FAILED}) {
            safely(
                    () ->
                            meters.counter(
                                    "secondbrain.document.cleanup.batches",
                                    "outcome",
                                    outcome.tag()));
        }
        safely(() -> meters.counter("secondbrain.document.cleanup.deleted"));
        for (var outcome :
                new Outcome[] {Outcome.REQUESTED, Outcome.UNCHANGED, Outcome.UNAVAILABLE}) {
            safely(
                    () ->
                            meters.counter(
                                    "secondbrain.document.deletions", "outcome", outcome.tag()));
        }
    }

    Operation upload(UUID correlationId) {
        return start("upload", "secondbrain.document.uploads", Outcome.UNAVAILABLE, correlationId);
    }

    Operation cleanup() {
        return start("cleanup", "secondbrain.document.cleanup.batches", Outcome.FAILED, null);
    }

    Operation deletion(UUID correlationId) {
        return start(
                "deletion", "secondbrain.document.deletions", Outcome.UNAVAILABLE, correlationId);
    }

    Operation indexingRequest(UUID correlationId) {
        return start(
                "indexing.request",
                "secondbrain.document.indexing.requests",
                Outcome.UNAVAILABLE,
                correlationId);
    }

    void deletedOriginal() {
        // Record after each actual deletion, even if a later entry fails in the same batch.
        safely(() -> meters.counter("secondbrain.document.cleanup.deleted").increment());
    }

    void cleanupCompleted(OriginalFileReconciler.Batch batch) {
        safely(
                () ->
                        LOG.atInfo()
                                .addKeyValue("operation", "original_cleanup")
                                .addKeyValue("outcome", "completed")
                                .addKeyValue("inspected", batch.inspected())
                                .addKeyValue("deleted", batch.deleted())
                                .addKeyValue("busy", batch.busy())
                                .log("Original file cleanup batch completed"));
    }

    void cleanupFailed() {
        safely(
                () ->
                        LOG.atWarn()
                                .addKeyValue("operation", "original_cleanup")
                                .addKeyValue("outcome", "failed")
                                .addKeyValue("code", "STORAGE_OR_DATABASE_UNAVAILABLE")
                                .log("Original file cleanup will retry"));
    }

    void scanCloseFailed() {
        safely(
                () ->
                        LOG.atWarn()
                                .addKeyValue("operation", "original_cleanup")
                                .addKeyValue("outcome", "close_failed")
                                .log("Original file scan could not close"));
    }

    private Operation start(String name, String counter, Outcome fallback, UUID correlationId) {
        var observation = Observation.NOOP;
        try {
            observation =
                    Observation.createNotStarted(
                                    "secondbrain.document." + name + ".duration", observations)
                            .contextualName("document." + name);
            if (correlationId != null) {
                observation.highCardinalityKeyValue("correlation_id", correlationId.toString());
            }
            // Inherit the current request as parent without replacing its thread-local scope.
            observation.start();
        } catch (RuntimeException unavailable) {
            safely(observation::stop);
            observation = Observation.NOOP;
            reportUnavailable();
        }
        return new Operation(name, observation, counter, fallback, correlationId);
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException unavailable) {
            reportUnavailable();
        }
    }

    private static void reportUnavailable() {
        try {
            LOG.atWarn()
                    .addKeyValue("code", "TELEMETRY_FAILED")
                    .log("Document telemetry unavailable");
        } catch (RuntimeException unavailable) {
            // A failing logging backend must not change an upload or cleanup result either.
        }
    }

    enum Outcome {
        ACCEPTED,
        REJECTED,
        UNAVAILABLE,
        COMPLETED,
        FAILED,
        REQUESTED,
        UNCHANGED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    final class Operation implements AutoCloseable {
        private final String name;
        private final Observation observation;
        private final String counter;
        private final UUID correlationId;
        private Outcome outcome;
        private boolean closed;

        private Operation(
                String name,
                Observation observation,
                String counter,
                Outcome outcome,
                UUID correlationId) {
            this.name = name;
            this.observation = observation;
            this.counter = counter;
            this.outcome = outcome;
            this.correlationId = correlationId;
        }

        void outcome(Outcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            safely(() -> meters.counter(counter, "outcome", outcome.tag()).increment());
            safely(() -> observation.lowCardinalityKeyValue("outcome", outcome.tag()));
            safely(observation::stop);
            if (correlationId != null) {
                safely(
                        () ->
                                LOG.atInfo()
                                        .addKeyValue("operation", "document_" + name)
                                        .addKeyValue("outcome", outcome.tag())
                                        .addKeyValue("correlation_id", correlationId.toString())
                                        .log(
                                                name.equals("upload")
                                                        ? "Document acceptance finished"
                                                        : name.equals("deletion")
                                                                ? "Document deletion request finished"
                                                                : "Document indexing request finished"));
            }
        }
    }
}
