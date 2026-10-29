package dev.secondbrain.search;

import dev.secondbrain.embeddings.EmbeddingFailure;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One interactive query request using the shared index model, outside database transactions. */
final class QueryEmbedding {
    private final EmbeddingProvider provider;
    private final EmbeddingModel model;

    QueryEmbedding(EmbeddingProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
        model = Objects.requireNonNull(provider.model(), "model");
    }

    EmbeddingModel model() {
        return model;
    }

    /** Empty means an explicit empty document selection, never provider failure or no matches. */
    Optional<EmbeddingVector> embed(SearchQuery query, EmbeddingModel indexModel) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(indexModel, "indexModel");
        if (query.scope() instanceof SearchScope.SelectedDocuments selected
                && selected.documentIds().isEmpty()) return Optional.empty();
        if (!model.equals(indexModel))
            throw new SearchFailure(SearchFailure.Reason.INCOMPATIBLE_MODEL, null, null);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Query embedding must run outside a transaction");
        checkInterrupted();
        final List<EmbeddingVector> response;
        try {
            response = provider.embed(List.of(query.text()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SearchFailure(SearchFailure.Reason.INTERRUPTED, null, interrupted);
        } catch (EmbeddingFailure failed) {
            var reason =
                    switch (failed.reason()) {
                        case RATE_LIMITED -> SearchFailure.Reason.RATE_LIMITED;
                        case QUOTA_EXHAUSTED -> SearchFailure.Reason.QUOTA_EXHAUSTED;
                        case TIMED_OUT -> SearchFailure.Reason.TIMED_OUT;
                        case INVALID_RESPONSE -> SearchFailure.Reason.INVALID_EMBEDDING;
                        case ACCESS_DENIED, MODEL_UNAVAILABLE, INVALID_INPUT, UNAVAILABLE ->
                                SearchFailure.Reason.PROVIDER_UNAVAILABLE;
                    };
            throw new SearchFailure(reason, failed.retryAt().orElse(null), failed);
        } catch (RuntimeException unavailable) {
            throw new SearchFailure(SearchFailure.Reason.PROVIDER_UNAVAILABLE, null, unavailable);
        }
        checkInterrupted();
        if (response == null
                || response.size() != 1
                || response.getFirst() == null
                || !model.equals(response.getFirst().model()))
            throw new SearchFailure(SearchFailure.Reason.INVALID_EMBEDDING, null, null);
        // EmbeddingVector already enforces dimensions, finite components and nonzero magnitude.
        return Optional.of(response.getFirst());
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new SearchFailure(SearchFailure.Reason.INTERRUPTED, null, null);
    }
}
