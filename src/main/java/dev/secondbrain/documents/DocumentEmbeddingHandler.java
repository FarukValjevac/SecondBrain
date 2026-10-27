package dev.secondbrain.documents;

import dev.secondbrain.embeddings.EmbeddingFailure;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;
import dev.secondbrain.events.DeliveryFailure;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Resumes missing batches; each write is fenced by both delivery ownership and document state. */
final class DocumentEmbeddingHandler implements EventHandler {
    static final String ID = "documents.embed-passages.v1";
    private final JdbcIndexingRepository indexes;
    private final JdbcEmbeddingRepository embeddings;
    private final EmbeddingProvider provider;
    private final EmbeddingModel model;
    private final int batchSize;
    private final DocumentIndexPublication publication;

    DocumentEmbeddingHandler(
            JdbcIndexingRepository indexes,
            JdbcEmbeddingRepository embeddings,
            EmbeddingProvider provider,
            int batchSize,
            DocumentIndexPublication publication) {
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.provider = Objects.requireNonNull(provider, "provider");
        model = Objects.requireNonNull(provider.model(), "provider model");
        if (batchSize < 1 || batchSize > 32)
            throw new IllegalArgumentException("Invalid embedding batch size");
        this.batchSize = batchSize;
        this.publication = Objects.requireNonNull(publication, "publication");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public EventEnvelope.Type eventType() {
        return PassagesPreparedEvent.TYPE;
    }

    @Override
    public Effect prepare(EventEnvelope event) {
        throw new IllegalStateException("Embedding preparation requires delivery checkpoints");
    }

    @Override
    public Effect prepare(EventEnvelope event, Checkpoint checkpoint) throws InterruptedException {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Embedding preparation must run outside a transaction");
        if (!eventType().equals(event.type()))
            throw new DeliveryFailure(new IllegalArgumentException("Unsupported embedding event"));
        // The document passage limit also bounds the number of batch operations in one attempt.
        for (int batch = 0; batch <= PassageSplitter.MAXIMUM_PASSAGES; batch++) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("Embedding preparation interrupted");
            var current =
                    indexes.findEligibleDesired(event.documentId())
                            .filter(index -> index.number() == event.processingVersion());
            if (current.isEmpty()) return () -> Outcome.SKIPPED;
            var version = current.orElseThrow();
            final List<JdbcEmbeddingRepository.Input> pending;
            try {
                pending = embeddings.findPendingBatch(version, model, batchSize);
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                throw new DeliveryFailure(invalid);
            }
            if (pending.isEmpty()) return () -> finish(version, event);
            if (batch == PassageSplitter.MAXIMUM_PASSAGES)
                throw new DeliveryFailure(
                        new IllegalStateException("Embedding progress limit exceeded"));
            final List<PassageEmbedding> vectors;
            try {
                var response =
                        provider.embed(
                                pending.stream().map(JdbcEmbeddingRepository.Input::text).toList());
                vectors = associate(pending, response);
            } catch (EmbeddingFailure failed) {
                throw new DeliveryFailure(
                        failed, failed.retryable(), failed.retryAt().orElse(null));
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                throw new DeliveryFailure(invalid);
            }
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("Embedding preparation interrupted");
            var saved =
                    checkpoint.save(
                            () -> {
                                if (!eligible(version)) return Outcome.SKIPPED;
                                try {
                                    embeddings.insertBatch(version, model, vectors);
                                } catch (IllegalArgumentException
                                        | IllegalStateException conflict) {
                                    throw new DeliveryFailure(conflict);
                                }
                                return Outcome.COMPLETED;
                            });
            if (saved.isEmpty() || saved.orElseThrow() == Outcome.SKIPPED)
                return () -> Outcome.SKIPPED;
        }
        throw new IllegalStateException("Unreachable embedding progress state");
    }

    private List<PassageEmbedding> associate(
            List<JdbcEmbeddingRepository.Input> inputs, List<EmbeddingVector> vectors) {
        if (vectors == null || vectors.size() != inputs.size())
            throw new IllegalStateException("Embedding response count is invalid");
        var result = new ArrayList<PassageEmbedding>(vectors.size());
        for (int index = 0; index < vectors.size(); index++) {
            var vector = vectors.get(index);
            if (vector == null || !model.equals(vector.model()))
                throw new IllegalStateException("Embedding response model is invalid");
            result.add(new PassageEmbedding(inputs.get(index).passageId(), vector));
        }
        return List.copyOf(result);
    }

    private boolean eligible(IndexingVersion version) {
        return indexes.lockEligibleDesired(version.documentId(), version.number())
                .filter(version::equals)
                .isPresent();
    }

    private Outcome finish(IndexingVersion version, EventEnvelope cause) {
        if (!eligible(version)) return Outcome.SKIPPED;
        try {
            embeddings.requireComplete(version, model);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new DeliveryFailure(invalid);
        }
        return publication.publish(version, cause);
    }
}
