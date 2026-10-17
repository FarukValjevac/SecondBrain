package dev.secondbrain.events;

import java.util.Objects;
import java.util.Optional;

/** Safe retry classification and optional fenced domain effects; exception text stays private. */
public final class DeliveryFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final boolean retryable;
    // Effects belong to this process; exception serialization must not capture collaborators.
    private final transient EventHandler.Effect effect;

    public DeliveryFailure(Throwable cause) {
        super("Permanent event handler failure", cause);
        retryable = false;
        effect = null;
    }

    public DeliveryFailure(Throwable cause, boolean retryable, EventHandler.Effect effect) {
        super("Event handler failure", cause);
        this.retryable = retryable;
        this.effect = Objects.requireNonNull(effect, "effect");
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
