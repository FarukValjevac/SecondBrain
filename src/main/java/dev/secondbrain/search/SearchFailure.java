package dev.secondbrain.search;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Safe interactive errors; raw provider failures are not part of the printable cause chain. */
public final class SearchFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Reason reason;
    private final Instant retryAt;
    private final transient Throwable diagnosticCause;

    SearchFailure(Reason reason, Instant retryAt, Throwable diagnosticCause) {
        super("Search failed: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
        this.retryAt = retryAt;
        this.diagnosticCause = diagnosticCause;
    }

    public Reason reason() {
        return reason;
    }

    /** A hint for a later user request, never a reason to wait or retry within this request. */
    public Optional<Instant> retryAt() {
        return Optional.ofNullable(retryAt);
    }

    public enum Reason {
        NO_COMPATIBLE_INDEX,
        DATABASE_UNAVAILABLE,
        INCOMPATIBLE_MODEL,
        INVALID_EMBEDDING,
        PROVIDER_UNAVAILABLE,
        RATE_LIMITED,
        QUOTA_EXHAUSTED,
        TIMED_OUT,
        INTERRUPTED
    }
}
