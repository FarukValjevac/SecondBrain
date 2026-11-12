package dev.secondbrain.search;

import dev.secondbrain.questions.ChatProvider;
import dev.secondbrain.questions.QuestionAnswerService;
import dev.secondbrain.questions.QuestionFailure;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Raw evidence/replies are written only to ignored artifacts for an explicit human review. */
final class GroundingEvaluation {
    enum Status {
        NOT_RUN,
        COMPLETED,
        INCOMPLETE
    }

    record CaseResult(
            String id,
            RetrievalEvaluation.Status status,
            QuestionAnswerService.Outcome outcome,
            boolean citationsAndFreshnessAccepted,
            List<String> suppliedPassages,
            List<String> citations,
            Integer embeddingRequests,
            Integer chatRequests,
            Double durationSeconds,
            String failureCode) {
        CaseResult {
            suppliedPassages = List.copyOf(suppliedPassages);
            citations = List.copyOf(citations);
        }
    }

    record Capture(
            String id,
            String question,
            List<String> expectedFacts,
            List<String> expectedSources,
            ChatProvider.Request suppliedRequest,
            ChatProvider.Reply candidate,
            String acceptedAnswer) {}

    record Report(
            Status status,
            String failureCode,
            String startedAt,
            String finishedAt,
            GroundingBudget.Usage usage,
            List<CaseResult> cases) {
        Report {
            cases = List.copyOf(cases);
        }
    }

    private final ReferenceIndex index;
    private final Clock clock;
    private final String startedAt;
    private final List<CaseResult> cases = new ArrayList<>();
    private final List<Capture> captures = new ArrayList<>();
    private Status status = Status.NOT_RUN;
    private String failureCode;
    private String finishedAt;

    GroundingEvaluation(ReferenceIndex index, Clock clock) {
        this.index = index;
        this.clock = clock;
        startedAt = clock.instant().toString();
        for (var reference : index.collection().cases())
            cases.add(
                    new CaseResult(
                            reference.id(),
                            RetrievalEvaluation.Status.NOT_RUN,
                            null,
                            false,
                            List.of(),
                            List.of(),
                            null,
                            null,
                            null,
                            null));
    }

    void run(
            Function<SearchQuery, QuestionAnswerService.Answer> answers,
            Consumer<List<String>> deletions,
            GroundingBudget chat,
            EvaluationBudget embeddings) {
        for (int i = 0; i < cases.size(); i++) {
            var reference = index.collection().cases().get(i);
            chat.clearCapture();
            int embeddingBefore = embeddings.usage().attemptedRequests();
            int chatBefore = chat.usage().attemptedRequests();
            long start = System.nanoTime();
            QuestionAnswerService.Answer answer = null;
            String code = null;
            try {
                deletions.accept(reference.deletedDocuments());
                start = System.nanoTime();
                answer =
                        answers.apply(
                                new SearchQuery(
                                        reference.query(), index.scopeFor(reference.scope())));
            } catch (QuestionFailure failure) {
                code = failure.reason().name();
            } catch (RuntimeException failure) {
                code = "EVALUATION_UNAVAILABLE";
            }
            if (chat.stopped() || embeddings.stopped()) code = "BUDGET_EXHAUSTED";
            var request = chat.lastRequest();
            cases.set(
                    i,
                    new CaseResult(
                            reference.id(),
                            code == null
                                    ? RetrievalEvaluation.Status.COMPLETED
                                    : RetrievalEvaluation.Status.FAILED,
                            answer == null ? null : answer.outcome(),
                            answer != null,
                            request == null
                                    ? List.of()
                                    : request.evidence().stream()
                                            .map(p -> p.passageId().toString())
                                            .toList(),
                            answer == null
                                    ? List.of()
                                    : answer.citations().stream()
                                            .map(p -> p.passageId().toString())
                                            .toList(),
                            embeddings.usage().attemptedRequests() - embeddingBefore,
                            chat.usage().attemptedRequests() - chatBefore,
                            (System.nanoTime() - start) / 1_000_000_000.0,
                            code));
            captures.add(
                    new Capture(
                            reference.id(),
                            reference.query(),
                            reference.expectedFacts(),
                            reference.expectedSources(),
                            request,
                            chat.lastReply(),
                            answer == null ? null : answer.text()));
            if (code != null) {
                fail(code);
                return;
            }
        }
        status = Status.COMPLETED;
        finishedAt = clock.instant().toString();
    }

    void fail(String code) {
        status = Status.INCOMPLETE;
        failureCode = code;
        finishedAt = clock.instant().toString();
    }

    Report report(GroundingBudget.Usage usage) {
        return new Report(status, failureCode, startedAt, finishedAt, usage, cases);
    }

    List<Capture> captures() {
        return List.copyOf(captures);
    }
}
