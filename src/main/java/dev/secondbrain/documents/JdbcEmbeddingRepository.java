package dev.secondbrain.documents;

import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingVector;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded immutable vector staging; the caller owns the fenced completion transaction. */
final class JdbcEmbeddingRepository {
    private static final int MAXIMUM_BATCH_SIZE = 32;
    private final JdbcClient jdbc;
    private final JdbcIndexingRepository indexes;
    private final TransactionTemplate mandatory;
    private final TransactionTemplate read;

    JdbcEmbeddingRepository(
            JdbcClient jdbc,
            JdbcIndexingRepository indexes,
            PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        mandatory = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setTimeout(5);
    }

    /** Reads only missing vectors in stable passage order; closes the transaction before HTTP. */
    List<Input> findPendingBatch(IndexingVersion version, EmbeddingModel model, int limit) {
        if (limit < 1 || limit > MAXIMUM_BATCH_SIZE)
            throw new IllegalArgumentException("Invalid embedding batch size");
        return Objects.requireNonNull(
                read.execute(
                        status -> {
                            requirePrepared(version, model);
                            return jdbc.sql(
                                            """
                SELECT p.passage_id, p.passage_text FROM document_passages p
                LEFT JOIN document_embeddings e ON e.passage_id = p.passage_id
                WHERE p.document_id = :id AND p.indexing_version = :version AND e.passage_id IS NULL
                ORDER BY p.sequence_number LIMIT :limit
                """)
                                    .withQueryTimeout(5)
                                    .param("id", version.documentId())
                                    .param("version", version.number())
                                    .param("limit", limit)
                                    .query(
                                            (row, index) ->
                                                    new Input(
                                                            row.getObject("passage_id", UUID.class),
                                                            row.getString("passage_text")))
                                    .list();
                        }));
    }

    /** Called only inside the final lease/document-fenced transaction; does not activate search. */
    void requireComplete(IndexingVersion version, EmbeddingModel model) {
        mandatory.executeWithoutResult(
                status -> {
                    int expected = requirePrepared(version, model);
                    long completed =
                            jdbc.sql(
                                            """
                SELECT count(*) FROM document_passages p JOIN document_embeddings e USING (passage_id)
                WHERE p.document_id = :id AND p.indexing_version = :version
                  AND e.model_id = :model AND e.dimensions = :dimensions
                """)
                                    .withQueryTimeout(5)
                                    .param("id", version.documentId())
                                    .param("version", version.number())
                                    .param("model", model.id())
                                    .param("dimensions", model.dimensions())
                                    .query(Long.class)
                                    .single();
                    if (completed != expected)
                        throw new IllegalStateException("Embedding generation is incomplete");
                });
    }

    private int requirePrepared(IndexingVersion version, EmbeddingModel model) {
        return jdbc.sql(
                        """
                SELECT passage_count, embedding_model_id, embedding_dimensions
                FROM document_index_versions WHERE document_id = :id AND indexing_version = :version
                  AND extraction_version = :source
                """)
                .withQueryTimeout(5)
                .param("id", version.documentId())
                .param("version", version.number())
                .param("source", version.extractionVersion())
                .query(
                        (row, index) -> {
                            var count = row.getObject("passage_count", Integer.class);
                            var boundModel = row.getString("embedding_model_id");
                            var dimensions = row.getObject("embedding_dimensions", Integer.class);
                            if (count == null
                                    || count < 0
                                    || count > PassageSplitter.MAXIMUM_PASSAGES
                                    || ((boundModel == null) != (dimensions == null))
                                    || (boundModel != null
                                            && (!model.id().equals(boundModel)
                                                    || model.dimensions() != dimensions)))
                                throw new IllegalStateException(
                                        "Prepared passages with a compatible embedding model are required");
                            return count;
                        })
                .optional()
                .orElseThrow(() -> new IllegalStateException("Embedding generation is missing"));
    }

    record Input(UUID passageId, String text) {
        Input {
            Objects.requireNonNull(passageId, "passageId");
            Objects.requireNonNull(text, "text");
        }
    }

    /** Returns the number of new rows. Identical retries keep the original stored values. */
    int insertBatch(IndexingVersion version, EmbeddingModel model, List<PassageEmbedding> batch) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(model, "model");
        var values = List.copyOf(batch);
        if (values.isEmpty() || values.size() > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("Embedding batch size must be between 1 and 32");
        }
        var ids = new HashSet<UUID>();
        for (var value : values) {
            if (!ids.add(value.passageId()) || !model.equals(value.vector().model())) {
                throw new IllegalArgumentException(
                        "Embedding batch identities or models are inconsistent");
            }
        }
        return Objects.requireNonNull(
                mandatory.execute(
                        status -> {
                            var current =
                                    indexes.lockEligibleDesired(
                                            version.documentId(), version.number());
                            if (current.isEmpty()
                                    || current.orElseThrow().extractionVersion()
                                            != version.extractionVersion()) {
                                throw new IllegalStateException(
                                        "Embedding index generation is no longer eligible");
                            }
                            int bound =
                                    jdbc.sql(
                                                    """
                    UPDATE document_index_versions SET embedding_model_id = :model, embedding_dimensions = :dimensions
                    WHERE document_id = :id AND indexing_version = :version AND passage_count IS NOT NULL
                      AND (embedding_model_id IS NULL OR (embedding_model_id = :model AND embedding_dimensions = :dimensions))
                    """)
                                            .withQueryTimeout(5)
                                            .param("model", model.id())
                                            .param("dimensions", model.dimensions())
                                            .param("id", version.documentId())
                                            .param("version", version.number())
                                            .update();
                            if (bound != 1) {
                                throw new IllegalStateException(
                                        "Prepared passages with a compatible embedding model are required");
                            }
                            int inserted = 0;
                            // One short transaction for at most 32 rows. Never replace an existing
                            // result on retry.
                            for (var value : values) {
                                var literal = literal(value.vector());
                                int added =
                                        jdbc.sql(
                                                        """
                        INSERT INTO document_embeddings(passage_id, document_id, indexing_version, model_id, dimensions, embedding)
                        VALUES (:passage, :id, :version, :model, :dimensions, CAST(:embedding AS vector))
                        ON CONFLICT (passage_id) DO NOTHING
                        """)
                                                .withQueryTimeout(5)
                                                .param("passage", value.passageId())
                                                .param("id", version.documentId())
                                                .param("version", version.number())
                                                .param("model", model.id())
                                                .param("dimensions", model.dimensions())
                                                .param("embedding", literal)
                                                .update();
                                if (added == 0
                                        && !jdbc.sql(
                                                        """
                        SELECT embedding = CAST(:embedding AS vector) FROM document_embeddings
                        WHERE passage_id = :passage AND document_id = :id AND indexing_version = :version
                          AND model_id = :model AND dimensions = :dimensions
                        """)
                                                .withQueryTimeout(5)
                                                .param("embedding", literal)
                                                .param("passage", value.passageId())
                                                .param("id", version.documentId())
                                                .param("version", version.number())
                                                .param("model", model.id())
                                                .param("dimensions", model.dimensions())
                                                .query(Boolean.class)
                                                .optional()
                                                .orElse(false)) {
                                    throw new IllegalStateException(
                                            "Stored embedding cannot change within an index generation");
                                }
                                inserted += added;
                            }
                            return inserted;
                        }));
    }

    private static String literal(EmbeddingVector vector) {
        var values = vector.values();
        var result = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) result.append(',');
            result.append(Float.toString(values[index]));
        }
        return result.append(']').toString();
    }
}
