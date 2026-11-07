package dev.secondbrain.questions;

import dev.secondbrain.search.SearchFailure;
import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchResult;
import dev.secondbrain.search.SearchScope;
import dev.secondbrain.search.SearchService;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Internal generation workflow; candidates still need citation and fresh visibility checks. */
final class QuestionService {
    private final SearchService search;
    private final ChatProvider chat;
    private final QuestionTelemetry telemetry;

    QuestionService(SearchService search, ChatProvider chat) {
        this(search, chat, QuestionTelemetry.disabled());
    }

    QuestionService(SearchService search, ChatProvider chat, QuestionTelemetry telemetry) {
        this.search = Objects.requireNonNull(search, "search");
        this.chat = Objects.requireNonNull(chat, "chat");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    Candidate generate(SearchQuery question) {
        Objects.requireNonNull(question, "question");
        try (var operation = telemetry.startQuestion()) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                operation.outcome(QuestionTelemetry.Outcome.REJECTED);
                throw new IllegalStateException("Questions must run outside a caller transaction");
            }
            try {
                var candidate = generateCandidate(question);
                if (candidate.context().isPresent())
                    operation.candidate(candidate.reply().outcome());
                else
                    operation.outcome(
                            question.scope() instanceof SearchScope.SelectedDocuments selected
                                            && selected.documentIds().isEmpty()
                                    ? QuestionTelemetry.Outcome.EMPTY_SCOPE
                                    : QuestionTelemetry.Outcome.NO_EVIDENCE);
                return candidate;
            } catch (QuestionFailure failure) {
                operation.failure(failure.reason());
                throw failure;
            }
        }
    }

    private Candidate generateCandidate(SearchQuery question) {
        checkInterrupted(QuestionFailure.Stage.RETRIEVAL);
        if (question.scope() instanceof SearchScope.SelectedDocuments selected
                && selected.documentIds().isEmpty()) return Candidate.withoutEvidence();

        var retrieved = retrieve(question);
        checkInterrupted(QuestionFailure.Stage.RETRIEVAL);
        Optional<EvidenceContext> context;
        try {
            context = EvidenceContext.select(question, retrieved);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new QuestionFailure(
                    QuestionFailure.Stage.CONTEXT,
                    QuestionFailure.Reason.INVALID_EVIDENCE,
                    null,
                    invalid);
        }
        if (context.isEmpty()) return Candidate.withoutEvidence();

        checkInterrupted(QuestionFailure.Stage.GENERATION);
        var reply = generate(context.orElseThrow().request());
        checkInterrupted(QuestionFailure.Stage.GENERATION);
        return new Candidate(reply, context);
    }

    private SearchResult retrieve(SearchQuery question) {
        try {
            return search.search(question);
        } catch (SearchFailure failure) {
            var reason =
                    switch (failure.reason()) {
                        case NO_COMPATIBLE_INDEX -> QuestionFailure.Reason.NO_COMPATIBLE_INDEX;
                        case DATABASE_UNAVAILABLE -> QuestionFailure.Reason.DATABASE_UNAVAILABLE;
                        case INCOMPATIBLE_MODEL -> QuestionFailure.Reason.INCOMPATIBLE_MODEL;
                        case INVALID_EMBEDDING -> QuestionFailure.Reason.INVALID_EMBEDDING;
                        case PROVIDER_UNAVAILABLE -> QuestionFailure.Reason.UNAVAILABLE;
                        case RATE_LIMITED -> QuestionFailure.Reason.RATE_LIMITED;
                        case QUOTA_EXHAUSTED -> QuestionFailure.Reason.QUOTA_EXHAUSTED;
                        case TIMED_OUT -> QuestionFailure.Reason.TIMED_OUT;
                        case INTERRUPTED -> QuestionFailure.Reason.INTERRUPTED;
                    };
            if (reason == QuestionFailure.Reason.INTERRUPTED) Thread.currentThread().interrupt();
            throw new QuestionFailure(
                    QuestionFailure.Stage.RETRIEVAL,
                    reason,
                    failure.retryAt().orElse(null),
                    failure);
        } catch (RuntimeException unavailable) {
            throw new QuestionFailure(
                    QuestionFailure.Stage.RETRIEVAL,
                    QuestionFailure.Reason.UNAVAILABLE,
                    null,
                    unavailable);
        }
    }

    private ChatProvider.Reply generate(ChatProvider.Request request) {
        try {
            var reply = chat.generate(request);
            if (reply == null)
                throw new ChatFailure(ChatFailure.Reason.INVALID_RESPONSE, null, null);
            return reply;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new QuestionFailure(
                    QuestionFailure.Stage.GENERATION,
                    QuestionFailure.Reason.INTERRUPTED,
                    null,
                    interrupted);
        } catch (ChatFailure failure) {
            var reason =
                    switch (failure.reason()) {
                        case ACCESS_DENIED -> QuestionFailure.Reason.ACCESS_DENIED;
                        case MODEL_UNAVAILABLE -> QuestionFailure.Reason.MODEL_UNAVAILABLE;
                        case INVALID_INPUT -> QuestionFailure.Reason.INVALID_INPUT;
                        case INVALID_RESPONSE -> QuestionFailure.Reason.INVALID_RESPONSE;
                        case OUTPUT_LIMIT -> QuestionFailure.Reason.OUTPUT_LIMIT;
                        case RATE_LIMITED -> QuestionFailure.Reason.RATE_LIMITED;
                        case QUOTA_EXHAUSTED -> QuestionFailure.Reason.QUOTA_EXHAUSTED;
                        case UNAVAILABLE -> QuestionFailure.Reason.UNAVAILABLE;
                        case TIMED_OUT -> QuestionFailure.Reason.TIMED_OUT;
                    };
            throw new QuestionFailure(
                    QuestionFailure.Stage.GENERATION,
                    reason,
                    failure.retryAt().orElse(null),
                    failure);
        } catch (RuntimeException unavailable) {
            throw new QuestionFailure(
                    QuestionFailure.Stage.GENERATION,
                    QuestionFailure.Reason.UNAVAILABLE,
                    null,
                    unavailable);
        }
    }

    private static void checkInterrupted(QuestionFailure.Stage stage) {
        if (Thread.currentThread().isInterrupted())
            throw new QuestionFailure(stage, QuestionFailure.Reason.INTERRUPTED, null, null);
    }

    /**
     * Retains precisely the supplied context, including model-declared insufficient information.
     */
    record Candidate(ChatProvider.Reply reply, Optional<EvidenceContext> context) {
        Candidate {
            Objects.requireNonNull(reply, "reply");
            Objects.requireNonNull(context, "context");
            if (context.isEmpty()
                    && reply.outcome() != ChatProvider.Outcome.INSUFFICIENT_INFORMATION)
                throw new IllegalArgumentException("An answer candidate requires evidence");
        }

        static Candidate withoutEvidence() {
            return new Candidate(
                    new ChatProvider.Reply(
                            ChatProvider.Outcome.INSUFFICIENT_INFORMATION, "", List.of()),
                    Optional.empty());
        }

        @Override
        public String toString() {
            return "QuestionCandidate[outcome="
                    + reply.outcome()
                    + ", passages="
                    + context.map(value -> value.sources().size()).orElse(0)
                    + "]";
        }
    }
}
