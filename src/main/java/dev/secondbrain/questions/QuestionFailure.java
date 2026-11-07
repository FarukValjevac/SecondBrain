package dev.secondbrain.questions;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Safe question failure; private collaborator diagnostics never enter printable causes. */
public final class QuestionFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Stage stage;
    private final Reason reason;
    private final Instant retryAt;
    private final transient Throwable diagnosticCause;

    QuestionFailure(Stage stage, Reason reason, Instant retryAt, Throwable diagnosticCause) {
        super(
                "Question failed: "
                        + Objects.requireNonNull(stage, "stage")
                        + "/"
                        + Objects.requireNonNull(reason, "reason"));
        this.stage = stage;
        this.reason = reason;
        this.retryAt = retryAt;
        this.diagnosticCause = diagnosticCause;
    }

    public Stage stage() {
        return stage;
    }

    public Reason reason() {
        return reason;
    }

    public Optional<Instant> retryAt() {
        return Optional.ofNullable(retryAt);
    }

    public enum Stage {
        RETRIEVAL,
        CONTEXT,
        GENERATION,
        CITATIONS,
        SOURCES
    }

    public enum Reason {
        NO_COMPATIBLE_INDEX,
        DATABASE_UNAVAILABLE,
        INCOMPATIBLE_MODEL,
        INVALID_EMBEDDING,
        INVALID_EVIDENCE,
        MISSING_CITATIONS,
        INVALID_CITATION,
        DUPLICATE_CITATION,
        SOURCE_UNAVAILABLE,
        ACCESS_DENIED,
        MODEL_UNAVAILABLE,
        INVALID_INPUT,
        INVALID_RESPONSE,
        OUTPUT_LIMIT,
        RATE_LIMITED,
        QUOTA_EXHAUSTED,
        UNAVAILABLE,
        TIMED_OUT,
        INTERRUPTED
    }
}
