package dev.secondbrain.documents;

import dev.secondbrain.embeddings.EmbeddingVector;

import java.util.Objects;
import java.util.UUID;

/** One staged vector linked to its stable passage ID. */
record PassageEmbedding(UUID passageId, EmbeddingVector vector) {
    PassageEmbedding {
        Objects.requireNonNull(passageId, "passageId");
        Objects.requireNonNull(vector, "vector");
    }
}
