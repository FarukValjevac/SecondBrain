package dev.secondbrain.questions;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Safe public failure; transport/parser causes are retained outside printable cause chains. */
public final class ChatFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Reason reason;
    private final Instant retryAt;
    private final transient Throwable diagnosticCause;

    ChatFailure(Reason reason, Instant retryAt, Throwable diagnosticCause) {
        super("Chat request failed: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
        this.retryAt = retryAt;
        this.diagnosticCause = diagnosticCause;
    }

    public Reason reason() {
        return reason;
    }

    public Optional<Instant> retryAt() {
        return Optional.ofNullable(retryAt);
    }

    public enum Reason {
        ACCESS_DENIED,
        MODEL_UNAVAILABLE,
        INVALID_INPUT,
        INVALID_RESPONSE,
        OUTPUT_LIMIT,
        RATE_LIMITED,
        QUOTA_EXHAUSTED,
        UNAVAILABLE,
        TIMED_OUT
    }
}
