package dev.secondbrain.search;

import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;

/** Synchronous retrieval API; provider I/O sits between two independent bounded database reads. */
public final class SearchService {
    private final QueryEmbedding embedding;
    private final JdbcPassageSearch passages;
    private final SearchProperties properties;

    SearchService(
            QueryEmbedding embedding, JdbcPassageSearch passages, SearchProperties properties) {
        this.embedding = Objects.requireNonNull(embedding, "embedding");
        this.passages = Objects.requireNonNull(passages, "passages");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public SearchResult search(SearchQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.scope() instanceof SearchScope.SelectedDocuments selected
                && selected.documentIds().isEmpty()) return new SearchResult(List.of());
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Search must run outside a caller transaction");
        if (Thread.currentThread().isInterrupted())
            throw new SearchFailure(SearchFailure.Reason.INTERRUPTED, null, null);
        try {
            var model = embedding.model();
            if (!passages.hasCompatibleIndex(query.scope(), model))
                throw new SearchFailure(SearchFailure.Reason.NO_COMPATIBLE_INDEX, null, null);
            var vector = embedding.embed(query, model).orElseThrow();
            return passages.retrieve(query, vector, properties.minimumScore());
        } catch (DataAccessException | TransactionException unavailable) {
            throw new SearchFailure(SearchFailure.Reason.DATABASE_UNAVAILABLE, null, unavailable);
        }
    }
}
