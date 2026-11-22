package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QueryTermsTest {
    @Test
    void keepsNamesNumbersAndRareWordsWithoutCommonWords() {
        assertThat(QueryTerms.of("Was sagt Hitschmann über § 35a SGB VIII und die KVT?"))
                .containsExactly("hitschmann", "35a", "sgb", "viii", "kvt");
        assertThat(QueryTerms.of("What is the role of Rogers in therapy?"))
                .containsExactly("role", "rogers", "therapy");
    }

    @Test
    void neverPassesFullTextOperatorsOrTooShortWords() {
        assertThat(QueryTerms.of("a & b | !c :* 'x' (Grawe) 4 12")).containsExactly("grawe", "12");
        assertThat(QueryTerms.of("Psychoedukation's")).containsExactly("psychoedukation");
    }

    @Test
    void ignoresTheConversationContextWordsAndLimitsTheCount() {
        assertThat(
                        QueryTerms.of(
                                "Earlier question in this conversation: Bindung\n\nCurrent question: und warum?"))
                .containsExactly("bindung");
        assertThat(
                        QueryTerms.of(
                                "alpha beta gamma delta epsilon zeta theta iota kappa lambda omikron sigma omega"))
                .hasSize(QueryTerms.MAXIMUM_TERMS);
        assertThat(QueryTerms.of("Was ist das und wie?")).isEmpty();
    }
}
