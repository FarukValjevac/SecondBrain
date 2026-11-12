package dev.secondbrain.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;

import java.util.Set;

/** Export only known signals and closed labels; local registries retain their ordinary meters. */
final class SafeMetricsFilter implements MeterFilter {
    private static final Set<String> NAMES =
            Set.of(
                    "process.uptime",
                    "secondbrain.event.backlog",
                    "secondbrain.event.backlog.available",
                    "secondbrain.event.backlog.oldest",
                    "secondbrain.event.workers.active",
                    "secondbrain.event.delivery.attempts",
                    "secondbrain.event.delivery.duration",
                    "secondbrain.document.uploads",
                    "secondbrain.document.upload.duration",
                    "secondbrain.document.cleanup.batches",
                    "secondbrain.document.cleanup.duration",
                    "secondbrain.document.cleanup.deleted",
                    "secondbrain.document.deletions",
                    "secondbrain.document.deletion.duration",
                    "secondbrain.document.deletion.backlog",
                    "secondbrain.document.deletion.backlog.available",
                    "secondbrain.document.deletion.backlog.oldest",
                    "secondbrain.document.deletion.retries",
                    "secondbrain.document.cleanup.retry.duration",
                    "secondbrain.document.indexing.requests",
                    "secondbrain.document.indexing.request.duration",
                    "secondbrain.document.processing.retries",
                    "secondbrain.document.processing.retry.duration",
                    "secondbrain.embedding.request.duration",
                    "secondbrain.embedding.requests",
                    "secondbrain.embedding.tokens",
                    "secondbrain.embedding.usage.reports",
                    "secondbrain.index.active",
                    "secondbrain.index.active.available",
                    "secondbrain.index.ready.duration",
                    "secondbrain.search.duration",
                    "secondbrain.search.requests",
                    "secondbrain.search.results",
                    "secondbrain.question.duration",
                    "secondbrain.question.requests",
                    "secondbrain.question.answer.duration",
                    "secondbrain.question.answers",
                    "secondbrain.chat.request.duration",
                    "secondbrain.chat.requests",
                    "secondbrain.chat.tokens",
                    "secondbrain.chat.usage.reports");
    private static final Set<String> OUTCOMES =
            Set.of(
                    "accepted",
                    "rejected",
                    "unavailable",
                    "completed",
                    "failed",
                    "requested",
                    "unchanged",
                    "success",
                    "retry",
                    "skipped",
                    "access_denied",
                    "model_unavailable",
                    "invalid_input",
                    "invalid_response",
                    "rate_limited",
                    "quota_exhausted",
                    "timed_out",
                    "interrupted",
                    "no_results",
                    "empty_scope",
                    "no_compatible_index",
                    "database_unavailable",
                    "incompatible_model",
                    "invalid_embedding",
                    "provider_unavailable",
                    "candidate_answer",
                    "insufficient_information",
                    "no_evidence",
                    "invalid_evidence",
                    "output_limit",
                    "answered",
                    "missing_citations",
                    "invalid_citation",
                    "duplicate_citation",
                    "source_unavailable");

    @Override
    public MeterFilterReply accept(Meter.Id id) {
        if (!NAMES.contains(id.getName())) return MeterFilterReply.DENY;
        for (var tag : id.getTags()) {
            boolean safe =
                    switch (tag.getKey()) {
                        case "outcome" -> OUTCOMES.contains(tag.getValue());
                        case "state" ->
                                Set.of(
                                                "ready",
                                                "delayed",
                                                "retrying",
                                                "running",
                                                "failed",
                                                "pending")
                                        .contains(tag.getValue());
                        case "kind" -> Set.of("input", "output", "total").contains(tag.getValue());
                        case "error" -> "none".equals(tag.getValue());
                        default -> false;
                    };
            if (!safe) return MeterFilterReply.DENY;
        }
        return MeterFilterReply.NEUTRAL;
    }

    @Override
    public Meter.Id map(Meter.Id id) {
        // Descriptions and arbitrary unit text are also outbound data, not just labels.
        var unit = "seconds".equals(id.getBaseUnit()) ? "seconds" : null;
        return new Meter.Id(id.getName(), Tags.of(id.getTags()), unit, null, id.getType());
    }
}
