package dev.secondbrain.search;

import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingVector;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Collectors;

/** Document-owned tables are a documented read-only projection, never a search write boundary. */
final class JdbcPassageSearch {
    // Matches document passage configuration v1; changing it requires a deliberate read-contract
    // update.
    private static final String ELIGIBLE =
            """
            WITH eligible_indexes AS MATERIALIZED (
                SELECT i.document_id, i.indexing_version
                FROM documents d JOIN document_index_versions i
                  ON i.document_id = d.document_id AND i.indexing_version = d.active_indexing_version
                JOIN LATERAL (
                    SELECT count(p.passage_id) AS passages, min(p.sequence_number) AS first_sequence,
                           max(p.sequence_number) AS last_sequence,
                           count(v.passage_id) FILTER (WHERE v.model_id = :model
                               AND v.dimensions = :dimensions AND vector_dims(v.embedding) = :dimensions
                               AND vector_norm(v.embedding) > 0) AS vectors
                    FROM document_passages p
                    LEFT JOIN document_embeddings v ON v.passage_id = p.passage_id
                        AND v.document_id = p.document_id AND v.indexing_version = p.indexing_version
                    WHERE p.document_id = i.document_id AND p.indexing_version = i.indexing_version
                ) complete ON complete.passages = i.passage_count AND complete.vectors = i.passage_count
                    AND complete.first_sequence = 1 AND complete.last_sequence = i.passage_count
                WHERE d.deleted_at IS NULL AND d.extraction_state = 'EXTRACTED'
                  AND i.extraction_version = d.processing_version
                  AND i.passage_configuration_version = 1 AND i.passage_count > 0
                  AND i.embedding_model_id = :model AND i.embedding_dimensions = :dimensions
            """;
    private final JdbcClient jdbc;
    private final TransactionTemplate reads;

    JdbcPassageSearch(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        reads = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        reads.setReadOnly(true);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setTimeout(5);
    }

    boolean hasCompatibleIndex(SearchScope scope, EmbeddingModel model) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(model, "model");
        if (empty(scope)) return false;
        return Boolean.TRUE.equals(
                reads.execute(
                        status ->
                                eligible(
                                                scope,
                                                model,
                                                "SELECT EXISTS (SELECT 1 FROM eligible_indexes)")
                                        .query(Boolean.class)
                                        .single()));
    }

    SearchResult retrieve(SearchQuery query, EmbeddingVector vector, double minimumScore) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(vector, "vector");
        new SearchProperties(minimumScore);
        if (empty(query.scope())) return new SearchResult(List.of());
        return Objects.requireNonNull(
                reads.execute(
                        status -> {
                            var matches =
                                    eligible(
                                                    query.scope(),
                                                    vector.model(),
                                                    """
                    , ranked AS (
                        SELECT p.passage_id, p.document_id, p.indexing_version, p.sequence_number,
                               p.passage_text, p.start_line, p.start_column, p.end_line, p.end_column, p.page_number,
                               1.0 - (v.embedding <=> CAST(:vector AS vector(1024))) AS score
                        FROM eligible_indexes i JOIN document_passages p
                          ON p.document_id = i.document_id AND p.indexing_version = i.indexing_version
                        JOIN document_embeddings v ON v.passage_id = p.passage_id
                          AND v.document_id = p.document_id AND v.indexing_version = p.indexing_version
                    )
                    SELECT * FROM ranked WHERE score BETWEEN -1.0 AND 1.0 AND score >= :minimum
                    ORDER BY score DESC, document_id, sequence_number, passage_id LIMIT :limit
                    """)
                                            .param("vector", literal(vector))
                                            .param("minimum", minimumScore)
                                            .param("limit", query.limit())
                                            .query(
                                                    (row, index) -> {
                                                        var page =
                                                                row.getObject(
                                                                        "page_number",
                                                                        Integer.class);
                                                        return new SearchResult.Match(
                                                                row.getObject(
                                                                        "passage_id", UUID.class),
                                                                row.getObject(
                                                                        "document_id", UUID.class),
                                                                row.getLong("indexing_version"),
                                                                row.getInt("sequence_number"),
                                                                row.getString("passage_text"),
                                                                new SearchResult.SourceLocation(
                                                                        row.getInt("start_line"),
                                                                        row.getInt("start_column"),
                                                                        row.getInt("end_line"),
                                                                        row.getInt("end_column"),
                                                                        page == null
                                                                                ? OptionalInt
                                                                                        .empty()
                                                                                : OptionalInt.of(
                                                                                        page)),
                                                                row.getDouble("score"));
                                                    })
                                            .list();
                            return new SearchResult(matches);
                        }));
    }

    /**
     * Fresh committed snapshot over only the bounded candidates; preserve ranking, never refill.
     */
    SearchResult retainVisible(SearchScope scope, EmbeddingModel model, SearchResult candidates) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.matches().isEmpty()) return candidates;
        var documents =
                candidates.matches().stream()
                        .map(SearchResult.Match::documentId)
                        .collect(Collectors.toCollection(HashSet::new));
        if (scope instanceof SearchScope.SelectedDocuments selected)
            documents.retainAll(selected.documentIds());
        if (documents.isEmpty()) return new SearchResult(List.of());
        var ids = candidates.matches().stream().map(SearchResult.Match::passageId).toList();
        return Objects.requireNonNull(
                reads.execute(
                        status -> {
                            var visible =
                                    new HashSet<>(
                                            eligible(
                                                            SearchScope.selected(documents),
                                                            model,
                                                            """
                    SELECT p.passage_id FROM eligible_indexes i JOIN document_passages p
                      ON p.document_id = i.document_id AND p.indexing_version = i.indexing_version
                    WHERE p.passage_id IN (:passages)
                    """)
                                                    .param("passages", ids)
                                                    .query(UUID.class)
                                                    .list());
                            return new SearchResult(
                                    candidates.matches().stream()
                                            .filter(match -> visible.contains(match.passageId()))
                                            .toList());
                        }));
    }

    private JdbcClient.StatementSpec eligible(
            SearchScope scope, EmbeddingModel model, String operation) {
        var selected = scope instanceof SearchScope.SelectedDocuments;
        var statement =
                jdbc.sql(
                                ELIGIBLE
                                        + (selected ? " AND d.document_id IN (:documents)\n" : "")
                                        + ")\n"
                                        + operation)
                        .withQueryTimeout(5)
                        .param("model", model.id())
                        .param("dimensions", model.dimensions());
        return selected
                ? statement.param(
                        "documents", ((SearchScope.SelectedDocuments) scope).documentIds())
                : statement;
    }

    private static boolean empty(SearchScope scope) {
        return scope instanceof SearchScope.SelectedDocuments selected
                && selected.documentIds().isEmpty();
    }

    private static String literal(EmbeddingVector vector) {
        var values = vector.values();
        var text = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) text.append(',');
            text.append(values[i]);
        }
        return text.append(']').toString();
    }
}
