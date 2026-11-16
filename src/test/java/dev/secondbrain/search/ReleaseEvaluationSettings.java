package dev.secondbrain.search;

import java.util.Properties;

/** Consent references are labels, never credentials, account IDs or arbitrary diagnostic text. */
record ReleaseEvaluationSettings(
        String authorizationReference,
        String freeEligibilityCheckedOn,
        EvaluationBudget.Limits embeddingsPerPhase,
        GroundingBudget.Limits chat) {
    static ReleaseEvaluationSettings from(Properties properties) {
        var embeddings = EvaluationBudget.Limits.from(properties);
        if (!"true".equals(properties.getProperty("evaluation.workersFreeConfirmed")))
            throw new IllegalArgumentException(
                    "Explicit Workers Free/model eligibility confirmation is required");
        String reference = properties.getProperty("evaluation.authorization", "");
        if (!reference.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("A safe authorization label is required");
        String date = properties.getProperty("evaluation.eligibilityCheckedOn", "");
        java.time.LocalDate.parse(date);
        return new ReleaseEvaluationSettings(
                reference, date, embeddings, GroundingBudget.Limits.from(properties));
    }
}
