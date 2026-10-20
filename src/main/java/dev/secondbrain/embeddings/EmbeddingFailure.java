package dev.secondbrain.embeddings;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Safe public diagnostics; raw transport/parser causes must not enter the logged cause chain. */
public final class EmbeddingFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Reason reason;
    private final Instant retryAt;
    private final transient Throwable diagnosticCause;

    EmbeddingFailure(Reason reason, Instant retryAt, Throwable diagnosticCause) {
        super("Embedding request failed: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
        this.retryAt = retryAt;
        this.diagnosticCause = diagnosticCause;
    }

    public Reason reason() {
        return reason;
    }

    public boolean retryable() {
        return reason.retryable;
    }

    public Optional<Instant> retryAt() {
        return Optional.ofNullable(retryAt);
    }

    public enum Reason {
        ACCESS_DENIED(false),
        MODEL_UNAVAILABLE(false),
        INVALID_INPUT(false),
        INVALID_RESPONSE(false),
        RATE_LIMITED(true),
        QUOTA_EXHAUSTED(true),
        UNAVAILABLE(true),
        TIMED_OUT(true);

        private final boolean retryable;

        Reason(boolean retryable) {
            this.retryable = retryable;
        }
    }
}
