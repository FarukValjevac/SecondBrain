package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;

class AnswerSignatureTest {
    private final AnswerSignature signature = new AnswerSignature();
    private final List<String> sources = List.of("a:b");

    @Test
    void acceptsOnlyTheExactAnswerItSigned() {
        var signed = signature.sign("Question?", "Answer [1].", sources);
        assertThat(signature.matches(signed, "Question?", "Answer [1].", sources)).isTrue();
        assertThat(signature.matches(signed, "Question!", "Answer [1].", sources)).isFalse();
        assertThat(signature.matches(signed, "Question?", "Changed answer.", sources)).isFalse();
        assertThat(signature.matches(signed, "Question?", "Answer [1].", List.of("a:c"))).isFalse();
        assertThat(signature.matches("forged", "Question?", "Answer [1].", sources)).isFalse();
    }

    @Test
    void movingTextBetweenFieldsChangesTheSignature() {
        assertThat(signature.sign("ab", "c", List.of()))
                .isNotEqualTo(signature.sign("a", "bc", List.of()));
    }

    @Test
    void aRestartedServerRejectsOlderPages() {
        var older = new AnswerSignature().sign("Q", "A", sources);
        assertThat(signature.matches(older, "Q", "A", sources)).isFalse();
    }
}
