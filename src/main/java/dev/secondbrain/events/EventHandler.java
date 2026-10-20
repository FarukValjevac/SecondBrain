package dev.secondbrain.events;

import java.util.Optional;

public interface EventHandler {
    String id();

    EventEnvelope.Type eventType();

    /** Runs outside a database transaction. Honor interruption and bound all external I/O. */
    Effect prepare(EventEnvelope event) throws Exception;

    /** Allows bounded progress to survive retries without releasing delivery ownership. */
    default Effect prepare(EventEnvelope event, Checkpoint checkpoint) throws Exception {
        return prepare(event);
    }

    @FunctionalInterface
    interface Checkpoint {
        /** Short fenced transaction. Empty means ownership was lost and preparation must stop. */
        Optional<Outcome> save(Effect effect);
    }

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
