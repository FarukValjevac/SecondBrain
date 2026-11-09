package dev.secondbrain.documents;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read only persisted current-stage facts; retry guards lock the owner after the delivery. */
final class JdbcProcessingRepository {
    private final JdbcClient jdbc;

    JdbcProcessingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> read(Set<UUID> ids) {
        if (ids.size() > 50) throw new IllegalArgumentException("Too many processing rows");
        if (ids.isEmpty()) return List.of();
        return jdbc.sql(
                        """
            SELECT d.document_id, d.extraction_state, d.extraction_failure,
                i.indexing_version AS desired_indexing_version, d.active_indexing_version, i.passage_count,
                COALESCE((SELECT max(t.line_number) FROM document_text_lines t
                    WHERE t.document_id = d.document_id AND t.processing_version = d.processing_version), 0) AS lines,
                (SELECT count(*) FROM document_passages p WHERE p.document_id = d.document_id
                    AND p.indexing_version = i.indexing_version) AS passages,
                (SELECT count(*) FROM document_embeddings v WHERE v.document_id = d.document_id
                    AND v.indexing_version = i.indexing_version) AS vectors,
                work.delivery_id, work.handler_id, work.state, work.retry_cycle, work.attempts,
                work.cycle_attempts, work.eligible_at, work.event_type, work.schema_version
            FROM documents d
            LEFT JOIN document_index_versions i ON i.document_id = d.document_id
                AND i.indexing_version = d.desired_indexing_version AND i.extraction_version = d.processing_version
            LEFT JOIN LATERAL (
                SELECT v.*, k.event_type, k.schema_version FROM event_keys k JOIN event_deliveries v USING(event_id)
                WHERE k.document_id = d.document_id AND (
                    (d.extraction_state <> 'EXTRACTED' AND k.processing_version = d.processing_version
                        AND k.event_type = 'DocumentAccepted' AND v.handler_id = 'documents.extract-text.v1')
                    OR (d.extraction_state = 'EXTRACTED' AND d.desired_indexing_version IS NULL
                        AND k.processing_version = d.processing_version
                        AND k.event_type = 'TextExtracted' AND v.handler_id = 'documents.request-index.v1')
                    OR (d.extraction_state = 'EXTRACTED' AND d.active_indexing_version IS DISTINCT FROM i.indexing_version
                        AND k.processing_version = i.indexing_version AND (
                            (i.passage_count IS NULL AND k.event_type = 'ReindexRequested'
                                AND v.handler_id = 'documents.prepare-passages.v1')
                            OR (i.passage_count IS NOT NULL AND k.event_type = 'PassagesPrepared'
                                AND v.handler_id = 'documents.embed-passages.v1')
                            OR (i.passage_count IS NOT NULL AND k.event_type = 'IndexPublicationRequested'
                                AND v.handler_id = 'documents.publish-index.v1'
                                AND EXISTS (SELECT 1 FROM event_keys ek JOIN event_deliveries ed USING(event_id)
                                    WHERE ek.document_id = d.document_id AND ek.processing_version = i.indexing_version
                                        AND ek.event_type = 'PassagesPrepared' AND ed.handler_id = 'documents.embed-passages.v1'
                                        AND ed.state = 'COMPLETED')))))
                ORDER BY CASE WHEN k.event_type = 'IndexPublicationRequested' THEN 0 ELSE 1 END
                LIMIT 1
            ) work ON true
            WHERE d.document_id IN (:ids) AND d.deleted_at IS NULL
            """)
                .withQueryTimeout(5)
                .param("ids", ids)
                .query(
                        (r, n) ->
                                new Row(
                                        r.getObject("document_id", UUID.class),
                                        ExtractionState.valueOf(r.getString("extraction_state")),
                                        Optional.ofNullable(r.getString("extraction_failure"))
                                                .map(TextExtractionException.Reason::valueOf),
                                        r.getObject("desired_indexing_version", Long.class),
                                        r.getObject("active_indexing_version", Long.class),
                                        r.getLong("lines"),
                                        r.getLong("passages"),
                                        r.getLong("vectors"),
                                        r.getObject("delivery_id", UUID.class),
                                        r.getString("handler_id"),
                                        r.getString("state"),
                                        r.getInt("retry_cycle"),
                                        r.getInt("attempts"),
                                        r.getInt("cycle_attempts"),
                                        Optional.ofNullable(r.getTimestamp("eligible_at"))
                                                .map(Timestamp::toInstant),
                                        r.getString("event_type"),
                                        r.getInt("schema_version")))
                .list();
    }

    boolean lockVisible(UUID id) {
        return jdbc.sql(
                        "SELECT document_id FROM documents WHERE document_id = :id AND deleted_at IS NULL FOR UPDATE")
                .withQueryTimeout(5)
                .param("id", id)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    void clearExtractionFailure(UUID id, Instant now) {
        jdbc.sql(
                        """
            UPDATE documents SET extraction_state = 'QUEUED', extraction_failure = NULL, updated_at = :now
            WHERE document_id = :id AND deleted_at IS NULL AND extraction_state <> 'EXTRACTED'
            """)
                .param("id", id)
                .param("now", Timestamp.from(now))
                .update();
    }

    record Row(
            UUID documentId,
            ExtractionState extraction,
            Optional<TextExtractionException.Reason> extractionFailure,
            Long desired,
            Long active,
            long lines,
            long passages,
            long vectors,
            UUID deliveryId,
            String handler,
            String state,
            int cycle,
            int attempts,
            int cycleAttempts,
            Optional<Instant> eligibleAt,
            String eventType,
            int schemaVersion) {}
}
