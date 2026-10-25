package dev.secondbrain.events;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Safe retry classification and optional fenced domain effects; exception text stays private. */
public final class DeliveryFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final boolean retryable;
    private final Instant retryAt;
    // Effects belong to this process; exception serialization must not capture collaborators.
    private final transient EventHandler.Effect effect;

    public DeliveryFailure(Throwable cause) {
        super("Permanent event handler failure", cause);
        retryable = false;
        retryAt = null;
        effect = null;
    }

    public DeliveryFailure(Throwable cause, boolean retryable, EventHandler.Effect effect) {
        super("Event handler failure", cause);
        this.retryable = retryable;
        retryAt = null;
        this.effect = Objects.requireNonNull(effect, "effect");
    }

    /** Provider hints delay eligibility; they never extend the configured attempt budget. */
    public DeliveryFailure(Throwable cause, boolean retryable, Instant retryAt) {
        super("Event handler failure", cause);
        this.retryable = retryable;
        this.retryAt = retryable ? retryAt : null;
        effect = null;
    }

    Optional<Instant> retryAt() {
        return Optional.ofNullable(retryAt);
    }

    boolean retryable() {
        return retryable;
    }

    Optional<EventHandler.Effect> effect() {
        return Optional.ofNullable(effect);
    }

    enum Code {
        INVALID_EVENT,
        HANDLER_MISSING,
        EVENT_UNSUPPORTED,
        SCHEMA_UNSUPPORTED,
        HANDLER_PERMANENT,
        HANDLER_TRANSIENT,
        TIMED_OUT,
        LEASE_EXPIRED,
        ATTEMPTS_EXHAUSTED
    }
}
