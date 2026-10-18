package dev.secondbrain.documents;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Index metadata only; durable deliveries remain the job records. */
final class JdbcIndexingRepository {
    private final JdbcClient jdbc;

    JdbcIndexingRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

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
