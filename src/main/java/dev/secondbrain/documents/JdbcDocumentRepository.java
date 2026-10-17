package dev.secondbrain.documents;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
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

    Optional<DocumentMetadata> findById(UUID documentId) {
        return jdbc.sql(
                        """
                        SELECT document_id, original_name, document_type, storage_key, content_sha256,
                               original_size_bytes, processing_version, extraction_state,
                               created_at, updated_at
                        FROM documents
                        WHERE document_id = :documentId
                        """)
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

    boolean ownsStorageId(UUID storageId) {
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
                        UPDATE documents SET extraction_state = 'EXTRACTED', updated_at = :completedAt
                        WHERE document_id = :documentId AND processing_version = :version
                          AND extraction_state IN ('QUEUED', 'PROCESSING', 'FAILED')
                        """)
                        .withQueryTimeout(5)
                        .param("documentId", documentId)
                        .param("version", processingVersion)
                        .param("completedAt", Timestamp.from(completedAt))
                        .update()
                == 1;
    }
}
