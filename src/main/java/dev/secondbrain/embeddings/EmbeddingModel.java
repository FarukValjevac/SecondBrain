package dev.secondbrain.embeddings;

/** Explicit compatibility contract; changing it requires deliberate reindexing. */
public record EmbeddingModel(String id, int dimensions) {
    public static final String BGE_M3_ID = "@cf/baai/bge-m3";
    public static final int BGE_M3_DIMENSIONS = 1024;

    public EmbeddingModel {
        if (!BGE_M3_ID.equals(id) || dimensions != BGE_M3_DIMENSIONS) {
            throw new IllegalArgumentException("Unsupported embedding model or dimensions");
        }
    }
}
