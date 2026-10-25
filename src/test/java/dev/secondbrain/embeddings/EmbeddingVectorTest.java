package dev.secondbrain.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmbeddingVectorTest {
    private final EmbeddingModel model = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);

    @Test
    void protectsValidatedComponentsFromCallerMutationAndDiagnosticDisclosure() {
        var components = new float[1024];
        components[0] = 0.125f;
        var vector = new EmbeddingVector(model, components);
        components[0] = Float.NaN;
        var returned = vector.values();
        returned[0] = Float.POSITIVE_INFINITY;
        assertThat(vector.values()[0]).isEqualTo(0.125f);
        assertThat(vector.model()).isEqualTo(model);
        assertThat(vector.toString()).isEqualTo("EmbeddingVector[dimensions=1024]");
    }

    @ParameterizedTest
    @ValueSource(floats = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
    void rejectsNonFiniteComponents(float value) {
        var components = new float[1024];
        components[0] = value;
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EmbeddingVector(model, components));
    }

    @Test
    void rejectsIncompatibleDimensionsAndZeroVectors() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EmbeddingVector(model, new float[768]));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EmbeddingVector(model, new float[1024]));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 768));
    }
}
