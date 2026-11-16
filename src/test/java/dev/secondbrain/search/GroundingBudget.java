package dev.secondbrain.search;

import dev.secondbrain.questions.ChatProvider;

import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Sequential, per-run chat allowance. Failed attempts count; missing token usage stays unknown. */
final class GroundingBudget implements ChatProvider {
    record Limits(
            int maximumRequests,
            long maximumTextBytes,
            int outputTokensPerRequest,
            long maximumReservedOutputTokens,
            long maximumReportedTokens) {
        Limits {
            if (maximumRequests < 1
                    || maximumRequests > 20
                    || maximumTextBytes < 1
                    || maximumTextBytes > 160000
                    || outputTokensPerRequest < 1
                    || outputTokensPerRequest > 2048
                    || maximumReservedOutputTokens < outputTokensPerRequest
                    || maximumReservedOutputTokens > 40960
                    || maximumReportedTokens < 1
                    || maximumReportedTokens > 200000)
                throw new IllegalArgumentException("Invalid grounding budgets");
        }

        static Limits from(Properties properties) {
            try {
                return new Limits(
                        Integer.parseInt(properties.getProperty("evaluation.chatRequests")),
                        Long.parseLong(properties.getProperty("evaluation.chatTextBytes")),
                        Integer.parseInt(properties.getProperty("evaluation.outputTokens")),
                        Long.parseLong(properties.getProperty("evaluation.reservedOutputTokens")),
                        Long.parseLong(properties.getProperty("evaluation.chatReportedTokens")));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Explicit chat budgets are required");
            }
        }
    }

    record Usage(
            int attemptedRequests,
            long submittedTextBytes,
            long reservedOutputTokens,
            long inputUsageReports,
            long outputUsageReports,
            long totalUsageReports,
            Long reportedInputTokens,
            Long reportedOutputTokens,
            Long reportedTotalTokens) {}

    private final ChatProvider delegate;
    private final Limits limits;
    private final MeterRegistry meters;
    private int requests;
    private long bytes;
    private boolean stopped;
    private Request lastRequest;
    private Reply lastReply;

    GroundingBudget(ChatProvider delegate, Limits limits, MeterRegistry meters) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.limits = java.util.Objects.requireNonNull(limits);
        this.meters = java.util.Objects.requireNonNull(meters);
    }

    void clearCapture() {
        lastRequest = null;
        lastReply = null;
    }

    Request lastRequest() {
        return lastRequest;
    }

    Reply lastReply() {
        return lastReply;
    }

    boolean stopped() {
        return stopped;
    }

    @Override
    public Reply generate(Request request) throws InterruptedException {
        long size =
                byteCount(request.question())
                        + request.evidence().stream().mapToLong(p -> byteCount(p.text())).sum();
        if (stopped
                || requests >= limits.maximumRequests()
                || size > limits.maximumTextBytes() - bytes
                || (long) (requests + 1) * limits.outputTokensPerRequest()
                        > limits.maximumReservedOutputTokens()
                || count("secondbrain.chat.tokens", "total") >= limits.maximumReportedTokens()) {
            stopped = true;
            throw new EvaluationBudget.LimitReached();
        }
        requests++;
        bytes += size;
        lastRequest = request;
        lastReply = delegate.generate(request);
        if (count("secondbrain.chat.tokens", "total") > limits.maximumReportedTokens()) {
            stopped = true;
            throw new EvaluationBudget.LimitReached();
        }
        return lastReply;
    }

    Usage usage() {
        return new Usage(
                requests,
                bytes,
                (long) requests * limits.outputTokensPerRequest(),
                reports("input"),
                reports("output"),
                reports("total"),
                tokens("input"),
                tokens("output"),
                tokens("total"));
    }

    private long reports(String kind) {
        return count("secondbrain.chat.usage.reports", kind);
    }

    private Long tokens(String kind) {
        return reports(kind) == 0 ? null : count("secondbrain.chat.tokens", kind);
    }

    private long count(String name, String kind) {
        var counter = meters.find(name).tag("kind", kind).counter();
        return counter == null ? 0 : (long) counter.count();
    }

    private static int byteCount(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
