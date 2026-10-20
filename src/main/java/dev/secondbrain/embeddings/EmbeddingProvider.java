package dev.secondbrain.embeddings;

import java.util.List;

/** Shared by passage indexing and query retrieval; contains no persistence or HTTP types. */
public interface EmbeddingProvider {
    EmbeddingModel model();

    /**
     * One bounded batch, returning one vector per input in input order. Implementations validate
     * inputs and responses, preserve interruption, and expose safe failures rather than response
     * bodies. Call outside database transactions; retries belong to the caller's bounded policy.
     */
    List<EmbeddingVector> embed(List<String> inputs) throws InterruptedException;
}
