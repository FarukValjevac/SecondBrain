package dev.secondbrain.config;

import java.util.Set;

/**
 * Outcome values that may leave the process in exported metrics and traces. One list for both
 * export boundaries, so a new outcome is allowed (or refused) in both places at once.
 */
final class TelemetryOutcomes {
    static final Set<String> SHARED =
            Set.of(
                    "accepted",
                    "access_denied",
                    "answered",
                    "candidate_answer",
                    "completed",
                    "database_unavailable",
                    "duplicate_citation",
                    "empty_scope",
                    "failed",
                    "incompatible_model",
                    "insufficient_information",
                    "interrupted",
                    "invalid_citation",
                    "invalid_embedding",
                    "invalid_evidence",
                    "invalid_input",
                    "invalid_response",
                    "missing_citations",
                    "model_unavailable",
                    "no_compatible_index",
                    "no_evidence",
                    "no_results",
                    "output_limit",
                    "provider_unavailable",
                    "quota_exhausted",
                    "rate_limited",
                    "rejected",
                    "requested",
                    "source_unavailable",
                    "success",
                    "timed_out",
                    "unavailable",
                    "unchanged");

    private TelemetryOutcomes() {}

    static Set<String> with(String... more) {
        var all = new java.util.HashSet<>(SHARED);
        all.addAll(Set.of(more));
        return Set.copyOf(all);
    }
}
