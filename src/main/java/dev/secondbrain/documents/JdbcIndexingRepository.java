package dev.secondbrain.documents;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Index metadata only; durable deliveries remain the job records. */
final class JdbcIndexingRepository {
    private final JdbcClient jdbc;

    JdbcIndexingRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Serialize allocation on the owning document; the caller also publishes its event. */
    Optional<IndexingVersion> request(UUID documentId, OptionalLong requestedVersion, Instant now) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(requestedVersion, "requestedVersion");
        Objects.requireNonNull(now, "now");
        if (requestedVersion.isPresent() && requestedVersion.getAsLong() < 1) {
            throw new IllegalArgumentException("Index version must be positive");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Index allocation requires a transaction");
        }
        var source = lockSource(documentId);
        if (source.isEmpty()) return Optional.empty();
        var selected = source.orElseThrow();
        long last =
                jdbc.sql(
                                """
                SELECT COALESCE(MAX(indexing_version), 0) FROM document_index_versions
                WHERE document_id = :id
                """)
                        .withQueryTimeout(5)
                        .param("id", documentId)
                        .query(Long.class)
                        .single();
        long target =
                requestedVersion.isPresent()
                        ? requestedVersion.getAsLong()
                        : Math.incrementExact(last);
        if (selected.desired() != null && target == selected.desired()) {
            return findEligibleDesired(documentId);
        }
        // An obsolete request must not select an old generation again.
        if (target <= last) return Optional.empty();
        jdbc.sql(
                        """
                INSERT INTO document_index_versions
                    (document_id, indexing_version, extraction_version, created_at)
                VALUES (:id, :version, :source, :now)
                """)
                .withQueryTimeout(5)
                .param("id", documentId)
                .param("version", target)
                .param("source", selected.extraction())
                .param("now", Timestamp.from(now))
                .update();
        jdbc.sql(
                        """
                UPDATE documents SET desired_indexing_version = :version, updated_at = :now
                WHERE document_id = :id
                """)
                .withQueryTimeout(5)
                .param("id", documentId)
                .param("version", target)
                .param("now", Timestamp.from(now))
                .update();
        return Optional.of(new IndexingVersion(documentId, target, selected.extraction(), now));
    }

    /** Reuses current work; a delayed extraction fact can never request a newer source. */
    Optional<IndexingVersion> enroll(UUID documentId, OptionalLong expectedSource, Instant now) {
        requireTransaction();
        var source = lockSource(documentId);
        if (source.isEmpty()
                || (expectedSource.isPresent()
                        && source.orElseThrow().extraction() != expectedSource.getAsLong())) {
            return Optional.empty();
        }
        var current = findEligibleDesired(documentId);
        return current.isPresent() ? current : request(documentId, OptionalLong.empty(), now);
    }

    /** Locks the owning row until stage effects and acknowledgment commit together. */
    Optional<IndexingVersion> lockEligibleDesired(UUID documentId, long version) {
        requireTransaction();
        if (lockSource(documentId).isEmpty()) return Optional.empty();
        return findEligibleDesired(documentId).filter(index -> index.number() == version);
    }

    List<UUID> findUnenrolled(int limit) {
        if (limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid enrollment batch");
        return jdbc.sql(
                        """
                SELECT d.document_id FROM documents d
                WHERE d.deleted_at IS NULL AND d.extraction_state = 'EXTRACTED'
                  AND EXISTS (SELECT 1 FROM document_extractions x
                      WHERE x.document_id = d.document_id AND x.processing_version = d.processing_version)
                  AND NOT EXISTS (SELECT 1 FROM event_keys k JOIN event_deliveries delivery USING (event_id)
                      WHERE k.document_id = d.document_id AND k.processing_version = d.processing_version
                        AND k.event_type = 'TextExtracted' AND delivery.handler_id = 'documents.request-index.v1')
                  AND NOT EXISTS (SELECT 1 FROM document_index_versions i
                      JOIN event_keys k ON k.document_id = i.document_id
                        AND k.processing_version = i.indexing_version AND k.event_type = 'ReindexRequested'
                      WHERE i.document_id = d.document_id AND i.indexing_version = d.desired_indexing_version
                        AND i.extraction_version = d.processing_version)
                ORDER BY d.created_at, d.document_id LIMIT :limit
                """)
                .withQueryTimeout(5)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    private Optional<Source> lockSource(UUID documentId) {
        return jdbc.sql(
                        """
                SELECT d.processing_version, d.desired_indexing_version
                FROM documents d
                WHERE d.document_id = :id AND d.deleted_at IS NULL
                  AND d.extraction_state = 'EXTRACTED'
                  AND EXISTS (SELECT 1 FROM document_extractions x
                      WHERE x.document_id = d.document_id
                        AND x.processing_version = d.processing_version)
                FOR UPDATE OF d
                """)
                .withQueryTimeout(5)
                .param("id", documentId)
                .query(
                        (row, index) ->
                                new Source(
                                        row.getLong("processing_version"),
                                        row.getObject("desired_indexing_version", Long.class)))
                .optional();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Index writes require a transaction");
        }
    }

    private record Source(long extraction, Long desired) {}

    /** Preflight eligibility only. Future writes must recheck version/deletion and lease fences. */
    Optional<IndexingVersion> findEligibleDesired(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId");
        return jdbc.sql(
                        """
                SELECT i.document_id, i.indexing_version, i.extraction_version, i.created_at
                FROM documents d
                JOIN document_index_versions i ON i.document_id = d.document_id
                    AND i.indexing_version = d.desired_indexing_version
                    AND i.extraction_version = d.processing_version
                WHERE d.document_id = :id AND d.deleted_at IS NULL
                    AND d.extraction_state = 'EXTRACTED'
                """)
                .withQueryTimeout(5)
                .param("id", documentId)
                .query(
                        (row, index) ->
                                new IndexingVersion(
                                        row.getObject("document_id", UUID.class),
                                                row.getLong("indexing_version"),
                                        row.getLong("extraction_version"),
                                                row.getTimestamp("created_at").toInstant()))
                .optional();
    }
}
