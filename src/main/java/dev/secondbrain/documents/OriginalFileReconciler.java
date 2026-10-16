package dev.secondbrain.documents;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.time.Clock;

/** Owns a background directory cursor, not upload or document lifecycle state. */
final class OriginalFileReconciler implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(OriginalFileReconciler.class);
    private final OriginalFileStore originals;
    private final JdbcDocumentRepository documents;
    private final OriginalCleanupProperties settings;
    private final Clock clock;
    private OriginalFileStore.Scan scan;
    private boolean closed;

    OriginalFileReconciler(
            OriginalFileStore originals,
            JdbcDocumentRepository documents,
            OriginalCleanupProperties settings,
            Clock clock) {
        this.originals = originals;
        this.documents = documents;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${secondbrain.documents.cleanup.interval:1m}",
            initialDelayString = "${secondbrain.documents.cleanup.interval:1m}")
    synchronized void cleanup() {
        try {
            var batch = reconcileBatch();
            LOG.atInfo()
                    .addKeyValue("operation", "original_cleanup")
                    .addKeyValue("outcome", "completed")
                    .addKeyValue("inspected", batch.inspected())
                    .addKeyValue("deleted", batch.deleted())
                    .addKeyValue("busy", batch.busy())
                    .log("Original file cleanup batch completed");
        } catch (IOException | RuntimeException failure) {
            try {
                closeScan();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            LOG.atWarn()
                    .addKeyValue("operation", "original_cleanup")
                    .addKeyValue("outcome", "failed")
                    .addKeyValue("code", "STORAGE_OR_DATABASE_UNAVAILABLE")
                    .log("Original file cleanup will retry");
        }
    }

    synchronized Batch reconcileBatch() throws IOException {
        if (closed) {
            return new Batch(0, 0, 0);
        }
        if (scan == null) {
            scan = originals.openScan();
        }
        var cutoff = clock.instant().minus(settings.gracePeriod());
        var inspected = 0;
        var deleted = 0;
        var busy = 0;
        while (inspected < settings.batchSize() && scan.hasNext()) {
            var storageId = scan.nextStorageId();
            inspected++;
            if (storageId.isEmpty()) {
                continue;
            }
            var result =
                    originals.deleteIfAbandoned(storageId.get(), cutoff, documents::ownsStorageId);
            switch (result) {
                case DELETED -> deleted++;
                case BUSY -> busy++;
                case RETAINED -> {}
            }
        }
        if (!scan.hasNext()) {
            closeScan();
        }
        return new Batch(inspected, deleted, busy);
    }

    private void closeScan() throws IOException {
        var previous = scan;
        scan = null;
        if (previous != null) {
            previous.close();
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        closed = true;
        try {
            closeScan();
        } catch (IOException failure) {
            LOG.atWarn()
                    .addKeyValue("operation", "original_cleanup")
                    .addKeyValue("outcome", "close_failed")
                    .log("Original file scan could not close");
        }
    }

    record Batch(int inspected, int deleted, int busy) {}
}
