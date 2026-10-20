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

    JdbcEmbeddingRepository(
            JdbcClient jdbc,
            JdbcIndexingRepository indexes,
            PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        mandatory = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
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
