package dev.secondbrain.documents;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class JdbcDocumentRepository {
    private final JdbcClient jdbc;

    JdbcDocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(DocumentMetadata document) {
        jdbc.sql(
                        """
                        INSERT INTO documents (
                            document_id, original_name, document_type, storage_key, content_sha256,
                            original_size_bytes, processing_version, extraction_state,
                            created_at, updated_at
                        ) VALUES (
                            :documentId, :originalName, :documentType, :storageId, :contentSha256,
                            :originalSizeBytes, :processingVersion, :extractionState,
                            :createdAt, :updatedAt
                        )
                        """)
                .param("documentId", document.documentId())
                .param("originalName", document.originalName())
                .param("documentType", document.documentType().name())
                .param("storageId", document.storageId())
                .param("contentSha256", document.contentSha256())
                .param("originalSizeBytes", document.originalSizeBytes())
                .param("processingVersion", document.processingVersion())
                .param("extractionState", document.extractionState().name())
                .param("createdAt", Timestamp.from(document.createdAt()))
                .param("updatedAt", Timestamp.from(document.updatedAt()))
                .update();
    }

    /** Visible metadata only; cleanup must use a deliberate tombstone-aware operation. */
    Optional<DocumentMetadata> findById(UUID documentId) {
        return jdbc.sql(
                        """
                        SELECT document_id, original_name, document_type, storage_key, content_sha256,
                               original_size_bytes, processing_version, extraction_state,
                               created_at, updated_at
                        FROM documents
                        WHERE document_id = :documentId AND deleted_at IS NULL
                        """)
                .withQueryTimeout(5)
                .param("documentId", documentId)
                .query(
                        (resultSet, rowNumber) ->
                                new DocumentMetadata(
                                        resultSet.getObject("document_id", UUID.class),
                                        resultSet.getString("original_name"),
                                        DocumentType.valueOf(resultSet.getString("document_type")),
                                        resultSet.getObject("storage_key", UUID.class),
                                        resultSet.getString("content_sha256"),
                                        resultSet.getLong("original_size_bytes"),
                                        resultSet.getLong("processing_version"),
                                        ExtractionState.valueOf(
                                                resultSet.getString("extraction_state")),
                                        resultSet.getTimestamp("created_at").toInstant(),
                                        resultSet.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    List<LibraryRow> list(int limit, long offset) {
        if (limit < 1 || limit > 51 || offset < 0) {
            throw new IllegalArgumentException("Invalid library page bounds");
        }
        return jdbc.sql(
                        """
                        SELECT d.document_id, d.original_name, d.extraction_state, d.created_at,
                               COALESCE(delivery.state, '') AS delivery_state
                        FROM documents d
                        LEFT JOIN event_keys k ON k.document_id = d.document_id
                            AND k.processing_version = d.processing_version
                            AND k.event_type = 'DocumentAccepted'
                        LEFT JOIN event_deliveries delivery ON delivery.event_id = k.event_id
                            AND delivery.handler_id = :handler
                        WHERE d.deleted_at IS NULL
                        ORDER BY d.created_at DESC, d.document_id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .withQueryTimeout(5)
                .param("handler", DocumentExtractionHandler.ID)
                .param("limit", limit)
                .param("offset", offset)
                .query(
                        (row, index) ->
                                new LibraryRow(
                                        row.getObject("document_id", UUID.class),
                                        row.getString("original_name"),
                                        ExtractionState.valueOf(row.getString("extraction_state")),
                                        row.getTimestamp("created_at").toInstant(),
                                        row.getString("delivery_state")))
                .list();
    }

    record LibraryRow(
            UUID documentId,
            String name,
            ExtractionState state,
            Instant createdAt,
            String deliveryState) {}

    /** Atomically invalidates older work; the returned version identifies the cleanup request. */
    Optional<Long> requestDeletion(UUID documentId, Instant now) {
        return jdbc.sql(
                        """
                        UPDATE documents SET deleted_at = :now, updated_at = :now,
                            processing_version = processing_version + 1
                        WHERE document_id = :id AND deleted_at IS NULL
                        RETURNING processing_version
                        """)
                .withQueryTimeout(5)
                .param("id", documentId)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .optional();
    }

    Optional<UUID> deletedStorageId(UUID id, long version) {
        return jdbc.sql(
                        """
                        SELECT storage_key FROM documents
                        WHERE document_id = :id AND processing_version = :version AND deleted_at IS NOT NULL
                        """)
                .withQueryTimeout(5)
                .param("id", id)
                .param("version", version)
                .query(UUID.class)
                .optional();
    }

    /** Cascades all stored text versions; event keys remain as non-content deduplication fences. */
    boolean finishDeletion(UUID id, long version, UUID storageId) {
        return jdbc.sql(
                                """
                        DELETE FROM documents WHERE document_id = :id AND processing_version = :version
                            AND deleted_at IS NOT NULL AND storage_key = :storage
                        """)
                        .withQueryTimeout(5)
                        .param("id", id)
                        .param("version", version)
                        .param("storage", storageId)
                        .update()
                == 1;
    }

    boolean ownsStorageId(UUID storageId) {
        // Deleted rows still own their original until the durable cleanup removes it.
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM documents WHERE storage_key = :storageId)")
                .withQueryTimeout(5)
                .param("storageId", storageId)
                .query(Boolean.class)
                .single();
    }

    /** Must participate in the same transaction as the text and downstream event writes. */
    boolean markExtracted(UUID documentId, long processingVersion, Instant completedAt) {
        return jdbc.sql(
                                """
                        UPDATE documents SET extraction_state = 'EXTRACTED', extraction_failure = NULL, updated_at = :completedAt
                        WHERE document_id = :documentId AND processing_version = :version
                          AND deleted_at IS NULL
                          AND extraction_state IN ('QUEUED', 'PROCESSING', 'FAILED')
                        """)
                        .withQueryTimeout(5)
                        .param("documentId", documentId)
                        .param("version", processingVersion)
                        .param("completedAt", Timestamp.from(completedAt))
                        .update()
                == 1;
    }

    boolean markFailed(UUID id, long version, TextExtractionException.Reason reason, Instant now) {
        return jdbc.sql(
                                """
                UPDATE documents SET extraction_state = 'FAILED', extraction_failure = :reason, updated_at = :now
                WHERE document_id = :id AND processing_version = :version AND extraction_state <> 'EXTRACTED'
                  AND deleted_at IS NULL
                """)
                        .withQueryTimeout(5)
                        .param("id", id)
                        .param("version", version)
                        .param("reason", reason.name())
                        .param("now", Timestamp.from(now))
                        .update()
                == 1;
    }

    Progress progress(UUID id, long version) {
        return jdbc.sql(
                        """
                SELECT d.extraction_failure, COALESCE(delivery.state, '') AS delivery_state
                FROM documents d
                LEFT JOIN event_keys k ON k.document_id = d.document_id
                    AND k.processing_version = d.processing_version AND k.event_type = 'DocumentAccepted'
                LEFT JOIN event_deliveries delivery ON delivery.event_id = k.event_id
                    AND delivery.handler_id = :handler
                WHERE d.document_id = :id AND d.processing_version = :version AND d.deleted_at IS NULL
                """)
                .withQueryTimeout(5)
                .param("id", id)
                .param("version", version)
                .param("handler", DocumentExtractionHandler.ID)
                .query(
                        (row, index) ->
                                new Progress(
                                        Optional.ofNullable(row.getString("extraction_failure"))
                                                .map(TextExtractionException.Reason::valueOf),
                                        row.getString("delivery_state")))
                .optional()
                .orElse(new Progress(Optional.empty(), ""));
    }

    boolean retry(UUID id, long version, Instant now) {
        return jdbc.sql(
                                """
                UPDATE documents d SET processing_version = processing_version + 1,
                    extraction_state = 'QUEUED', extraction_failure = NULL, updated_at = :now
                WHERE d.document_id = :id AND d.processing_version = :version
                  AND d.deleted_at IS NULL
                  AND d.extraction_state <> 'EXTRACTED'
                  AND EXISTS (SELECT 1 FROM event_keys k JOIN event_deliveries delivery USING (event_id)
                      WHERE k.document_id = d.document_id AND k.processing_version = d.processing_version
                        AND k.event_type = 'DocumentAccepted' AND delivery.handler_id = :handler
                        AND delivery.state = 'FAILED')
                """)
                        .withQueryTimeout(5)
                        .param("id", id)
                        .param("version", version)
                        .param("handler", DocumentExtractionHandler.ID)
                        .param("now", Timestamp.from(now))
                        .update()
                == 1;
    }

    record Progress(Optional<TextExtractionException.Reason> failure, String deliveryState) {}

    CleanupBacklog cleanupBacklog(Instant now) {
        return jdbc.sql(
                        """
                        SELECT count(*) FILTER (WHERE COALESCE(delivery.state, '') <> 'FAILED') AS pending,
                               count(*) FILTER (WHERE delivery.state = 'FAILED') AS failed,
                               GREATEST(0, COALESCE(EXTRACT(EPOCH FROM
                                   (CAST(:now AS timestamptz) - min(d.deleted_at))), 0)) AS age
                        FROM documents d
                        LEFT JOIN event_keys k ON k.document_id = d.document_id
                            AND k.processing_version = d.processing_version
                            AND k.event_type = 'DocumentDeletionRequested'
                        LEFT JOIN event_deliveries delivery ON delivery.event_id = k.event_id
                            AND delivery.handler_id = :handler
                        WHERE d.deleted_at IS NOT NULL
                        """)
                .withQueryTimeout(5)
                .param("now", Timestamp.from(now))
                .param("handler", DocumentDeletionHandler.ID)
                .query(
                        (row, index) ->
                                new CleanupBacklog(
                                        row.getLong("pending"),
                                        row.getLong("failed"),
                                        row.getDouble("age")))
                .single();
    }

    record CleanupBacklog(long pending, long failed, double age) {}
}
