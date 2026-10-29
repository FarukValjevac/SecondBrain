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
    private final SearchTelemetry telemetry;

    SearchService(
            QueryEmbedding embedding,
            JdbcPassageSearch passages,
            SearchProperties properties,
            SearchTelemetry telemetry) {
        this.embedding = Objects.requireNonNull(embedding, "embedding");
        this.passages = Objects.requireNonNull(passages, "passages");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public SearchResult search(SearchQuery query) {
        Objects.requireNonNull(query, "query");
        try (var operation = telemetry.start()) {
            if (query.scope() instanceof SearchScope.SelectedDocuments selected
                    && selected.documentIds().isEmpty()) {
                operation.emptyScope();
                return new SearchResult(List.of());
            }
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                operation.rejected();
                throw new IllegalStateException("Search must run outside a caller transaction");
            }
            try {
                checkInterrupted();
                var model = embedding.model();
                if (!passages.hasCompatibleIndex(query.scope(), model))
                    throw new SearchFailure(SearchFailure.Reason.NO_COMPATIBLE_INDEX, null, null);
                var vector = embedding.embed(query, model).orElseThrow();
                var ranked = passages.retrieve(query, vector, properties.minimumScore());
                checkInterrupted();
                var visible = passages.retainVisible(query.scope(), model, ranked);
                checkInterrupted();
                operation.success(visible.matches().size());
                return visible;
            } catch (SearchFailure failure) {
                operation.failure(failure.reason());
                throw failure;
            } catch (DataAccessException | TransactionException unavailable) {
                operation.failure(SearchFailure.Reason.DATABASE_UNAVAILABLE);
                throw new SearchFailure(
                        SearchFailure.Reason.DATABASE_UNAVAILABLE, null, unavailable);
            }
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new SearchFailure(SearchFailure.Reason.INTERRUPTED, null, null);
    }
}
