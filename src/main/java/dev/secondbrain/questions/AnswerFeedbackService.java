package dev.secondbrain.questions;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Records explicit answer ratings locally; nothing is sent to a provider. */
public final class AnswerFeedbackService {
    private final JdbcAnswerFeedbackRepository repository;
    private final Clock clock;
    private final Optional<MeterRegistry> meters;

    AnswerFeedbackService(
            JdbcAnswerFeedbackRepository repository, Clock clock, Optional<MeterRegistry> meters) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    /**
     * Stores one rating. Returns false when a cited document has been deleted since the answer was
     * shown, so no feedback is kept for content that is gone.
     */
    public boolean record(
            boolean helpful,
            Optional<AnswerFeedback.Reason> reason,
            String question,
            String answer,
            List<AnswerFeedback.Source> sources) {
        var feedback =
                new AnswerFeedback(
                        UUID.randomUUID(),
                        helpful,
                        helpful ? Optional.empty() : reason,
                        question,
                        answer,
                        sources,
                        clock.instant());
        boolean stored = repository.insert(feedback);
        if (stored) count(helpful ? "helpful" : "not_helpful");
        return stored;
    }

    /** Stored feedback for evaluation review, excluding anything citing a deleted document. */
    public List<AnswerFeedback> export() {
        return repository.exportable();
    }

    private void count(String rating) {
        meters.ifPresent(
                registry -> {
                    try {
                        registry.counter("secondbrain.questions.feedback", "rating", rating)
                                .increment();
                    } catch (RuntimeException unavailable) {
                        // Telemetry never changes whether feedback was stored.
                        LoggerFactory.getLogger(AnswerFeedbackService.class)
                                .atWarn()
                                .addKeyValue("code", "TELEMETRY_FAILED")
                                .log("Feedback metric unavailable");
                    }
                });
    }
}
