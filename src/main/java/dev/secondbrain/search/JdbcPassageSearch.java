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
import java.util.Set;
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
                  AND i.passage_configuration_version IN (1, 2) AND i.passage_count > 0
                  AND i.embedding_model_id = :model AND i.embedding_dimensions = :dimensions
            """;
    private static final String SCORED =
            """
            , scored AS (
                SELECT p.passage_id, p.document_id, p.indexing_version, p.sequence_number,
                       p.passage_text, p.start_line, p.start_column, p.end_line, p.end_column, p.page_number,
                       p.search_terms,
                       1.0 - (v.embedding <=> CAST(:vector AS vector(1024))) AS score
                FROM eligible_indexes i JOIN document_passages p
                  ON p.document_id = i.document_id AND p.indexing_version = i.indexing_version
                JOIN document_embeddings v ON v.passage_id = p.passage_id
                  AND v.document_id = p.document_id AND v.indexing_version = p.indexing_version
            ), valid AS MATERIALIZED (
                SELECT * FROM scored WHERE score BETWEEN -1.0 AND 1.0
            )
            """;

    /** Meaning search alone, for queries without usable exact words. */
    private static final String MEANING_ONLY =
            SCORED
                    + """
                    SELECT * FROM valid WHERE score >= :minimum
                    ORDER BY score DESC, document_id, sequence_number, passage_id LIMIT :limit
                    """;

    /**
     * Meaning and exact-word search merged by reciprocal rank fusion (k = 60). Meaning candidates
     * must reach the minimum cosine score; exact-word candidates must contain words that are rare
     * among the searched passages (inverse document frequency), so common words never pull in
     * unrelated passages. Both lists come from the same eligible, in-scope passages.
     */
    private static final String HYBRID =
            SCORED
                    + """
                    , terms AS (SELECT DISTINCT unnest(string_to_array(:terms, ' ')) AS term)
                    , hits AS (
                        SELECT v.passage_id, t.term FROM valid v JOIN terms t
                          ON v.search_terms @@ to_tsquery('simple', t.term || ':*')
                    ), rarity AS (
                        -- A word missing from every searched passage weighs most: its absence shows
                        -- that the topic is not covered here.
                        SELECT t.term, ln(GREATEST((SELECT count(*) FROM valid), 1)::float8
                            / GREATEST(count(h.passage_id), 1)) AS weight
                        FROM terms t LEFT JOIN hits h USING (term) GROUP BY t.term
                    ), query_weight AS (
                        SELECT sum(weight) AS total FROM rarity
                    ), words AS (
                        SELECT h.passage_id, sum(r.weight) AS weight FROM hits h JOIN rarity r USING (term)
                        GROUP BY h.passage_id
                        HAVING sum(r.weight) >= :minimumWeight
                           AND sum(r.weight) >= :coverage * (SELECT total FROM query_weight)
                    ), meaning_ranked AS (
                        SELECT passage_id, row_number() OVER (
                            ORDER BY score DESC, document_id, sequence_number, passage_id) AS position
                        FROM valid WHERE score >= :minimum
                    ), words_ranked AS (
                        SELECT w.passage_id, row_number() OVER (
                            ORDER BY w.weight DESC, v.score DESC, v.passage_id) AS position
                        FROM words w JOIN valid v USING (passage_id)
                    ), fused AS (
                        SELECT passage_id, sum(1.0 / (60 + position)) AS fusion FROM (
                            SELECT * FROM meaning_ranked WHERE position <= :candidates
                            UNION ALL SELECT * FROM words_ranked WHERE position <= :candidates
                        ) candidates GROUP BY passage_id
                    )
                    SELECT v.* FROM valid v JOIN fused f USING (passage_id)
                    ORDER BY f.fusion DESC, v.score DESC, v.document_id, v.sequence_number, v.passage_id
                    LIMIT :limit
                    """;

    /** Words must be rarer than in about one passage in three to count as exact-word evidence. */
    static final double MINIMUM_WORD_WEIGHT = 1.0;

    /**
     * A passage found by words alone must cover more than half (60%) of the query's word weight, so
     * a few scattered generic words never stand in for the topic of the question.
     */
    static final double MINIMUM_COVERAGE = 0.6;

    private static int candidates(int limit) {
        return Math.max(20, limit * 4);
    }

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
                                // Not materialized: the existence check stops at the first
                                // complete index instead of checking every document.
                                eligible(
                                                scope,
                                                model,
                                                "SELECT EXISTS (SELECT 1 FROM eligible_indexes)",
                                                false)
                                        .query(Boolean.class)
                                        .single()));
    }

    /**
     * True when an explicit selection names no document that is still visible. Cleanup removes
     * deleted rows in the background, so a deleted ID soon looks exactly like an unknown one; both
     * mean there is nothing to search. Visible but unindexed documents return false.
     */
    boolean selectionHasNoVisibleDocument(SearchScope scope) {
        Objects.requireNonNull(scope, "scope");
        if (!(scope instanceof SearchScope.SelectedDocuments selected)
                || selected.documentIds().isEmpty()) return false;
        return Boolean.TRUE.equals(
                reads.execute(
                        status ->
                                jdbc.sql(
                                                """
                                                SELECT NOT EXISTS (SELECT 1 FROM documents
                                                    WHERE document_id IN (:documents) AND deleted_at IS NULL)
                                                """)
                                        .withQueryTimeout(5)
                                        .param("documents", selected.documentIds())
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
                            var terms = QueryTerms.of(query.text());
                            var matches =
                                    (terms.isEmpty()
                                                    ? eligible(
                                                            query.scope(),
                                                            vector.model(),
                                                            MEANING_ONLY)
                                                    : eligible(
                                                                    query.scope(),
                                                                    vector.model(),
                                                                    HYBRID)
                                                            .param("terms", String.join(" ", terms))
                                                            .param(
                                                                    "candidates",
                                                                    candidates(query.limit()))
                                                            .param(
                                                                    "minimumWeight",
                                                                    MINIMUM_WORD_WEIGHT)
                                                            .param("coverage", MINIMUM_COVERAGE))
                                            .param("vector", literal(vector))
                                            .param("minimum", minimumScore)
                                            .param("limit", query.limit())
                                            .query(JdbcPassageSearch::match)
                                            .list();
                            return new SearchResult(matches);
                        }));
    }

    /**
     * A document's own passages closest to the mean of its stored vectors, best first. These are
     * the passages most typical of the document, chosen without any provider call.
     */
    SearchResult representative(UUID documentId, EmbeddingModel model, int limit) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(model, "model");
        if (limit < 1) throw new IllegalArgumentException("Limit must be positive");
        return Objects.requireNonNull(
                reads.execute(
                        status ->
                                new SearchResult(
                                        eligible(
                                                        SearchScope.selected(Set.of(documentId)),
                                                        model,
                                                        """
                    , source AS (
                        SELECT avg(v.embedding) AS centroid
                        FROM eligible_indexes i JOIN document_embeddings v
                          ON v.document_id = i.document_id AND v.indexing_version = i.indexing_version
                    )
                    SELECT p.passage_id, p.document_id, p.indexing_version, p.sequence_number,
                           p.passage_text, p.start_line, p.start_column, p.end_line, p.end_column, p.page_number,
                           1.0 - (v.embedding <=> s.centroid) AS score
                    FROM eligible_indexes i JOIN document_passages p
                      ON p.document_id = i.document_id AND p.indexing_version = i.indexing_version
                    JOIN document_embeddings v ON v.passage_id = p.passage_id
                      AND v.document_id = p.document_id AND v.indexing_version = p.indexing_version
                    CROSS JOIN source s
                    WHERE s.centroid IS NOT NULL
                    ORDER BY score DESC NULLS LAST, p.sequence_number LIMIT :limit
                    """)
                                                .param("limit", limit)
                                                .query(JdbcPassageSearch::match)
                                                .list())));
    }

    private static SearchResult.Match match(java.sql.ResultSet row, int index)
            throws java.sql.SQLException {
        var page = row.getObject("page_number", Integer.class);
        return new SearchResult.Match(
                row.getObject("passage_id", UUID.class),
                row.getObject("document_id", UUID.class),
                row.getLong("indexing_version"),
                row.getInt("sequence_number"),
                row.getString("passage_text"),
                new SearchResult.SourceLocation(
                        row.getInt("start_line"),
                        row.getInt("start_column"),
                        row.getInt("end_line"),
                        row.getInt("end_column"),
                        page == null ? OptionalInt.empty() : OptionalInt.of(page)),
                row.getDouble("score"));
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
        return eligible(scope, model, operation, true);
    }

    private JdbcClient.StatementSpec eligible(
            SearchScope scope, EmbeddingModel model, String operation, boolean materialized) {
        var selected = scope instanceof SearchScope.SelectedDocuments;
        var statement =
                jdbc.sql(
                                (materialized
                                                ? ELIGIBLE
                                                : ELIGIBLE.replace(
                                                        "AS MATERIALIZED", "AS NOT MATERIALIZED"))
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
