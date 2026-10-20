package dev.secondbrain.embeddings;

import java.util.Objects;

/** Private numerical content with defensive copies; never render components in diagnostics. */
public final class EmbeddingVector {
    private final EmbeddingModel model;
    private final float[] values;

    public EmbeddingVector(EmbeddingModel model, float[] values) {
        this.model = Objects.requireNonNull(model, "model");
        this.values = Objects.requireNonNull(values, "values").clone();
        if (this.values.length != model.dimensions()) {
            throw new IllegalArgumentException("Embedding dimensions do not match the model");
        }
        boolean nonzero = false;
        for (float value : this.values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding components must be finite");
            }
            nonzero |= value != 0;
        }
        if (!nonzero) {
            throw new IllegalArgumentException("A zero embedding cannot support cosine retrieval");
        }
    }

    public EmbeddingModel model() {
        return model;
    }

    public float[] values() {
        return values.clone();
    }

    @Override
    public String toString() {
        return "EmbeddingVector[dimensions=" + model.dimensions() + "]";
    }
}
