package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

class ReleaseQualityGatesTest {
    private final ReferenceIndex index =
            ReferenceIndex.prepare(RetrievalReferenceCollection.loadRelease());
    @TempDir Path directory;

    ReleaseQualityGatesTest() throws Exception {}

    @Test
    void missingMeasurementsAndUnreviewedAnswersNeverPass() {
        var retrieval =
                new RetrievalEvaluation(
                        index, new EvaluationBudget.Limits(22, 8192, 8192), Clock.systemUTC());
        assertThat(
                        ReleaseQualityGates.retrieval(
                                        index,
                                        retrieval.report(new EvaluationBudget.Usage(0, 0, 0, 0, 0)))
                                .verdict())
                .isEqualTo(ReleaseQualityGates.Verdict.NOT_RUN);
        assertThat(ReleaseQualityGates.grounding(index, report(), List.of()).verdict())
                .isEqualTo(ReleaseQualityGates.Verdict.NOT_RUN);
        assertThat(
                        ReleaseQualityGates.grounding(index, report(), List.of())
                                .supportedCompleteAnswers())
                .isZero();
    }

    @Test
    void validCitationsDoNotMakeAContradictedClaimSupported() {
        var reviews = new ArrayList<>(reviews());
        var original = reviews.getFirst();
        reviews.set(
                0,
                new ReleaseQualityGates.Review(
                        original.id(),
                        true,
                        false,
                        List.of(
                                new ReleaseQualityGates.Claim(
                                        "ArrayList forbids duplicates.",
                                        ReleaseQualityGates.Support.CONTRADICTED,
                                        original.claims().getFirst().evidencePassages(),
                                        "The supplied text says duplicates are permitted.")),
                        original.facts(),
                        "All material claims inspected."));
        var score = ReleaseQualityGates.grounding(index, report(), reviews);
        assertThat(report().cases().getFirst().citationsAndFreshnessAccepted()).isTrue();
        assertThat(score.verdict()).isEqualTo(ReleaseQualityGates.Verdict.FAIL);
        assertThat(score.unsupportedOrContradictedClaims()).isEqualTo(1);
        assertThat(score.citationFailures()).isZero();
    }

    @Test
    void completeControlledReviewsPassButAreNotLiveEvidence() {
        var score = ReleaseQualityGates.grounding(index, report(), reviews());
        assertThat(score.verdict()).isEqualTo(ReleaseQualityGates.Verdict.PASS);
        assertThat(score.supportedCompleteAnswers()).isEqualTo(14);
        assertThat(score.correctInsufficientAnswers()).isEqualTo(6);
    }

    @Test
    void omittedFactsAndIncorrectAbsenceFailWithoutInventingUnsupportedClaims() {
        var results = new ArrayList<>(report().cases());
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            if (r.outcome()
                    == dev.secondbrain.questions.QuestionAnswerService.Outcome
                            .INSUFFICIENT_INFORMATION)
                results.set(
                        i,
                        new GroundingEvaluation.CaseResult(
                                r.id(),
                                r.status(),
                                dev.secondbrain.questions.QuestionAnswerService.Outcome.ANSWERED,
                                true,
                                r.suppliedPassages(),
                                r.citations(),
                                0,
                                0,
                                .01,
                                null));
        }
        var score =
                ReleaseQualityGates.grounding(
                        index,
                        new GroundingEvaluation.Report(
                                GroundingEvaluation.Status.COMPLETED,
                                null,
                                "date",
                                "date",
                                null,
                                results),
                        reviews());
        assertThat(score.correctInsufficientAnswers()).isZero();
        assertThat(score.verdict()).isEqualTo(ReleaseQualityGates.Verdict.FAIL);
    }

    @Test
    void reviewsCannotSubstituteForeignEvidenceOrOmitExpectedFacts() {
        var reviews = new ArrayList<>(reviews());
        var r = reviews.getFirst();
        reviews.set(
                0,
                new ReleaseQualityGates.Review(
                        r.id(),
                        true,
                        false,
                        List.of(
                                new ReleaseQualityGates.Claim(
                                        "claim",
                                        ReleaseQualityGates.Support.SUPPORTED,
                                        List.of("foreign"),
                                        "reason")),
                        r.facts(),
                        "notes"));
        assertThatThrownBy(() -> ReleaseQualityGates.grounding(index, report(), reviews))
                .isInstanceOf(IllegalArgumentException.class);
        reviews.set(
                0,
                new ReleaseQualityGates.Review(
                        r.id(), true, false, r.claims(), List.of(), "notes"));
        assertThatThrownBy(() -> ReleaseQualityGates.grounding(index, report(), reviews))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reviewIsBoundToTheActualSavedConfigurationAnswersAndEvidence() throws Exception {
        ReleaseEvaluationArtifacts.write(
                directory, "configuration.json", java.util.Map.of("corpus", "java-study-v2"));
        ReleaseEvaluationArtifacts.write(directory, "grounding.json", report());
        ReleaseEvaluationArtifacts.write(directory, "evidence.json", List.of());
        ReleaseEvaluationArtifacts.write(
                directory,
                "review.json",
                new ReleaseQualityGates.ReviewFile(
                        ReleaseEvaluationArtifacts.digest(directory), "local-reviewer", reviews()));
        ReleaseEvaluationArtifacts.review(directory, index);
        ReleaseEvaluationArtifacts.write(directory, "evidence.json", List.of("changed"));
        assertThatThrownBy(() -> ReleaseEvaluationArtifacts.review(directory, index))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void instructionFollowingIsASeparateReleaseFailure() {
        var reviews = new ArrayList<>(reviews());
        var r = reviews.getLast();
        reviews.set(
                reviews.size() - 1,
                new ReleaseQualityGates.Review(
                        r.id(),
                        true,
                        true,
                        r.claims(),
                        r.facts(),
                        "Model followed quoted instruction."));
        assertThat(ReleaseQualityGates.grounding(index, report(), reviews).instructionFailures())
                .isEqualTo(1);
    }

    @Test
    void retrievalGatesMeasureRecallAndScopeLeaksIndependentlyFromGrounding() {
        assertThat(ReleaseQualityGates.retrieval(index, retrieval(false, false)).verdict())
                .isEqualTo(ReleaseQualityGates.Verdict.PASS);
        var misses = ReleaseQualityGates.retrieval(index, retrieval(true, false));
        assertThat(misses.verdict()).isEqualTo(ReleaseQualityGates.Verdict.FAIL);
        assertThat(misses.macroRecallAt5()).isLessThan(.90);
        var leak = ReleaseQualityGates.retrieval(index, retrieval(false, true));
        assertThat(leak.verdict()).isEqualTo(ReleaseQualityGates.Verdict.FAIL);
        assertThat(leak.scopeOrDeletionLeaks()).isPositive();
    }

    private RetrievalEvaluation.Report retrieval(boolean miss, boolean leak) {
        var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            var observations = io.micrometer.observation.ObservationRegistry.create();
            observations
                    .observationConfig()
                    .observationHandler(
                            new io.micrometer.core.instrument.observation
                                    .DefaultMeterObservationHandler(meters));
            var telemetry = new SearchTelemetry(meters, observations);
            var limits = new EvaluationBudget.Limits(22, 8192, 8192);
            var budget =
                    new EvaluationBudget(
                            EvaluationBudgetTest.provider(
                                    meters,
                                    new java.util.concurrent.atomic.AtomicInteger(),
                                    0,
                                    false),
                            limits,
                            meters);
            var evaluation = new RetrievalEvaluation(index, limits, Clock.systemUTC());
            var number = new java.util.concurrent.atomic.AtomicInteger();
            evaluation.run(
                    query -> {
                        int n = number.getAndIncrement();
                        var reference = index.collection().cases().get(n);
                        var expected = index.passagesForSources(reference.expectedSources());
                        var matches =
                                index.documents().stream()
                                        .filter(
                                                d ->
                                                        expected.contains(d.passageId())
                                                                || (leak
                                                                        && reference
                                                                                .id()
                                                                                .equals(
                                                                                        "wrong-scope")))
                                        .filter(d -> !miss || n > 1)
                                        .limit(5)
                                        .map(
                                                d ->
                                                        new SearchResult.Match(
                                                                d.passageId(),
                                                                d.documentId(),
                                                                1,
                                                                1,
                                                                d.text(),
                                                                d.location(),
                                                                .9))
                                        .toList();
                        try (var operation = telemetry.start()) {
                            operation.success(matches.size());
                            return new SearchResult(matches);
                        }
                    },
                    labels -> {},
                    budget,
                    meters);
            return evaluation.report(budget.usage());
        } finally {
            meters.close();
        }
    }

    @Test
    void incompleteFactCoverageCannotPassEvenWithSupportedClaims() {
        var reviews = new ArrayList<>(reviews());
        for (int i = 0; i < 2; i++) {
            var r = reviews.get(i);
            var facts =
                    r.facts().stream()
                            .map(
                                    f ->
                                            new ReleaseQualityGates.Fact(
                                                    f.expectedFact(),
                                                    ReleaseQualityGates.Coverage.OMITTED,
                                                    List.of(),
                                                    "Expected fact is missing from answer."))
                            .toList();
            reviews.set(
                    i,
                    new ReleaseQualityGates.Review(
                            r.id(),
                            true,
                            false,
                            r.claims(),
                            facts,
                            "All material claims inspected."));
        }
        var score = ReleaseQualityGates.grounding(index, report(), reviews);
        assertThat(score.verdict()).isEqualTo(ReleaseQualityGates.Verdict.FAIL);
        assertThat(score.supportedCompleteAnswers()).isEqualTo(12);
        assertThat(score.unsupportedOrContradictedClaims()).isZero();
    }

    private GroundingEvaluation.Report report() {
        return new GroundingEvaluation.Report(
                GroundingEvaluation.Status.COMPLETED,
                null,
                "date",
                "date",
                null,
                index.collection().cases().stream()
                        .map(
                                c -> {
                                    var ids =
                                            index.passagesForSources(c.expectedSources()).stream()
                                                    .map(Object::toString)
                                                    .toList();
                                    return new GroundingEvaluation.CaseResult(
                                            c.id(),
                                            RetrievalEvaluation.Status.COMPLETED,
                                            ids.isEmpty()
                                                    ? dev.secondbrain.questions
                                                            .QuestionAnswerService.Outcome
                                                            .INSUFFICIENT_INFORMATION
                                                    : dev.secondbrain.questions
                                                            .QuestionAnswerService.Outcome.ANSWERED,
                                            true,
                                            ids,
                                            ids,
                                            0,
                                            0,
                                            .01,
                                            null);
                                })
                        .toList());
    }

    private List<ReleaseQualityGates.Review> reviews() {
        return index.collection().cases().stream()
                .map(
                        c -> {
                            var ids =
                                    index.passagesForSources(c.expectedSources()).stream()
                                            .map(Object::toString)
                                            .toList();
                            return new ReleaseQualityGates.Review(
                                    c.id(),
                                    true,
                                    false,
                                    ids.isEmpty()
                                            ? List.of()
                                            : List.of(
                                                    new ReleaseQualityGates.Claim(
                                                            "Controlled supported example",
                                                            ReleaseQualityGates.Support.SUPPORTED,
                                                            ids,
                                                            "Inspected supplied evidence.")),
                                    c.expectedFacts().stream()
                                            .map(
                                                    f ->
                                                            new ReleaseQualityGates.Fact(
                                                                    f,
                                                                    ReleaseQualityGates.Coverage
                                                                            .PRESENT,
                                                                    ids,
                                                                    "Fact covered."))
                                            .toList(),
                                    "Controlled scoring fixture only.");
                        })
                .toList();
    }
}
