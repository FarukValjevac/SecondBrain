package dev.secondbrain.search;

import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;

import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Per-run limits, reserved before I/O. Reported tokens are a post-response stop, not a tokenizer.
 */
final class EvaluationBudget implements EmbeddingProvider {
    record Limits(int maximumRequests, long maximumInputBytes, long maximumReportedTokens) {
        Limits {
            if (maximumRequests < 1
                    || maximumRequests > 32
                    || maximumInputBytes < 1
                    || maximumInputBytes > 16384
                    || maximumReportedTokens < 1
                    || maximumReportedTokens > 8192)
                throw new IllegalArgumentException("Evaluation limits exceed supported bounds");
        }

        static Limits from(Properties properties) {
            if (!"true".equals(properties.getProperty("evaluation.allowLive")))
                throw new IllegalArgumentException(
                        "Explicit evaluation.allowLive=true consent is required on Workers Free");
            try {
                return new Limits(
                        Integer.parseInt(properties.getProperty("evaluation.maxRequests")),
                        Long.parseLong(properties.getProperty("evaluation.maxInputBytes")),
                        Long.parseLong(properties.getProperty("evaluation.maxReportedTokens")));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(
                        "Explicit integer evaluation budgets are required");
            }
        }
    }

    record Usage(
            int attemptedRequests,
            long submittedInputBytes,
            long successfulRequests,
            long usageReports,
            long reportedInputTokens) {}

    private final EmbeddingProvider delegate;
    private final Limits limits;
    private final MeterRegistry meters;
    private int requests;
    private long bytes;
    private boolean stopped;

    EvaluationBudget(EmbeddingProvider delegate, Limits limits, MeterRegistry meters) {
        this.delegate = Objects.requireNonNull(delegate);
        this.limits = Objects.requireNonNull(limits);
        this.meters = Objects.requireNonNull(meters);
    }

    /** Reject an insufficient declared allowance before spending any requests. */
    static void preflight(ReferenceIndex index, Limits limits) {
        long bytes =
                index.documents().stream().mapToLong(document -> byteCount(document.text())).sum();
        int requests = 1; // One batch for all three current reference passages.
        for (var reference : index.collection().cases()) {
            var query = new SearchQuery(reference.query(), index.scopeFor(reference.scope()));
            if (query.scope() instanceof SearchScope.SelectedDocuments selected
                    && selected.documentIds().isEmpty()) continue;
            requests++;
            bytes += byteCount(query.text());
        }
        if (requests > limits.maximumRequests() || bytes > limits.maximumInputBytes())
            throw new IllegalArgumentException(
                    "Evaluation budget cannot cover the declared reference run");
    }

    @Override
    public EmbeddingModel model() {
        return delegate.model();
    }

    @Override
    public List<EmbeddingVector> embed(List<String> inputs) throws InterruptedException {
        var copy = List.copyOf(inputs);
        long nextBytes = copy.stream().mapToLong(EvaluationBudget::byteCount).sum();
        if (stopped
                || requests >= limits.maximumRequests()
                || nextBytes > limits.maximumInputBytes() - bytes
                || usage().reportedInputTokens() >= limits.maximumReportedTokens()) {
            stopped = true;
            throw new LimitReached();
        }
        requests++;
        bytes += nextBytes; // Failed/ambiguous attempts still consume the local budget.
        var vectors = delegate.embed(copy);
        if (usage().reportedInputTokens() > limits.maximumReportedTokens()) {
            stopped = true;
            throw new LimitReached();
        }
        return vectors;
    }

    boolean stopped() {
        return stopped;
    }

    Usage usage() {
        return new Usage(
                requests,
                bytes,
                (long) counter("secondbrain.embedding.requests", "outcome", "success"),
                (long) counter("secondbrain.embedding.usage.reports"),
                (long) counter("secondbrain.embedding.tokens"));
    }

    private double counter(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private static long byteCount(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    static final class LimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LimitReached() {
            super("Evaluation budget exhausted");
        }
    }
}
