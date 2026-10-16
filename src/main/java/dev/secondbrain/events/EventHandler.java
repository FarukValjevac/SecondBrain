package dev.secondbrain.events;

public interface EventHandler {
    String id();

    EventEnvelope.Type eventType();

    /** Runs outside a database transaction. Honor interruption and bound all external I/O. */
    Effect prepare(EventEnvelope event) throws Exception;

    @FunctionalInterface
    interface Effect {
        /**
         * Short database-only work in the fenced acknowledgment transaction. Feature handlers must
         * check document version/deletion/prerequisites here and make their effects idempotent.
         */
        Outcome apply();
    }

    enum Outcome {
        COMPLETED,
        SKIPPED
    }
}
