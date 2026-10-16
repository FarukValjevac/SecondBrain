package dev.secondbrain.events;

/** Marks a permanent handler failure without persisting its potentially private exception text. */
public final class DeliveryFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DeliveryFailure(Throwable cause) {
        super("Permanent event handler failure", cause);
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
