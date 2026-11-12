package dev.secondbrain.search;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Frozen release-gates-v1. Human support judgments are never inferred from valid citations. */
final class ReleaseQualityGates {
    private ReleaseQualityGates() {}

    enum Verdict {
        PASS,
        FAIL,
        NOT_RUN
    }

    enum Support {
        SUPPORTED,
        UNSUPPORTED,
        CONTRADICTED
    }

    enum Coverage {
        PRESENT,
        OMITTED,
        MISINTERPRETED
    }

    record Claim(String text, Support support, List<String> evidencePassages, String rationale) {}

    record Fact(
            String expectedFact,
            Coverage coverage,
            List<String> evidencePassages,
            String rationale) {}

    record Review(
            String id,
            boolean allClaimsReviewed,
            Boolean obeyedReferenceInstructions,
            List<Claim> claims,
            List<Fact> facts,
            String notes) {}

    record ReviewFile(String groundingSha256, String reviewer, List<Review> cases) {}

    record RetrievalScore(
            Verdict verdict,
            Double macroRecallAt5,
            int measuredCases,
            int fullCoverageCases,
            int multiDocumentFullCoverage,
            int scopeOrDeletionLeaks) {}

    record GroundingScore(
            Verdict verdict,
            int measuredCases,
            int reviewedCases,
            int supportedCompleteAnswers,
            int correctInsufficientAnswers,
            int unsupportedOrContradictedClaims,
            int instructionFailures,
            int citationFailures) {}

    static RetrievalScore retrieval(ReferenceIndex index, RetrievalEvaluation.Report report) {
        int measured = 0, complete = 0, multi = 0, leaks = 0, positives = 0;
        double recall = 0;
        for (var result : report.cases()) {
            if (result.status() != RetrievalEvaluation.Status.COMPLETED) continue;
            measured++;
            var reference =
                    index.collection().cases().stream()
                            .filter(c -> c.id().equals(result.id()))
                            .findFirst()
                            .orElseThrow();
            if (reference.id().equals("empty-selection")
                    && !Integer.valueOf(0).equals(result.embeddingRequests())) leaks++;
            var allowed =
                    index.documents().stream()
                            .filter(d -> !reference.deletedDocuments().contains(d.label()))
                            .filter(
                                    d ->
                                            reference.scope().mode()
                                                            == RetrievalReferenceCollection.Mode.ALL
                                                    || reference
                                                            .scope()
                                                            .documents()
                                                            .contains(d.label()))
                            .map(d -> d.passageId().toString())
                            .collect(Collectors.toSet());
            leaks +=
                    (int)
                            result.returnedPassages().stream()
                                    .filter(p -> !allowed.contains(p.passageId()))
                                    .count();
            if (result.recallAtK() != null) {
                positives++;
                recall += result.recallAtK();
                if (result.recallAtK() == 1) {
                    complete++;
                    if (result.expectedPassages().size() > 1) multi++;
                }
            }
        }
        Double macro = positives == 0 ? null : recall / positives;
        boolean failure =
                leaks > 0
                        || (measured == 20
                                && (positives != 14 || macro < .90 || complete < 13 || multi < 3));
        return new RetrievalScore(
                failure
                        ? Verdict.FAIL
                        : measured == 20 && report.status() == RetrievalEvaluation.Status.COMPLETED
                                ? Verdict.PASS
                                : Verdict.NOT_RUN,
                macro,
                measured,
                complete,
                multi,
                leaks);
    }

    static GroundingScore grounding(
            ReferenceIndex index, GroundingEvaluation.Report report, List<Review> reviews) {
        Map<String, Review> byId =
                reviews.stream().collect(Collectors.toMap(Review::id, Function.identity()));
        Set<String> known =
                index.collection().cases().stream()
                        .map(RetrievalReferenceCollection.QueryCase::id)
                        .collect(Collectors.toSet());
        if (!known.containsAll(byId.keySet()))
            throw new IllegalArgumentException("Unknown review case");
        int measured = 0,
                reviewed = 0,
                supported = 0,
                absent = 0,
                bad = 0,
                instructions = 0,
                citations = 0;
        for (var result : report.cases()) {
            if (Set.of(
                            "MISSING_CITATIONS",
                            "INVALID_CITATION",
                            "DUPLICATE_CITATION",
                            "SOURCE_UNAVAILABLE")
                    .contains(result.failureCode() == null ? "" : result.failureCode()))
                citations++;
            if (result.status() != RetrievalEvaluation.Status.COMPLETED) continue;
            measured++;
            var reference =
                    index.collection().cases().stream()
                            .filter(c -> c.id().equals(result.id()))
                            .findFirst()
                            .orElseThrow();
            if (reference.id().equals("empty-selection")
                    && (!Integer.valueOf(0).equals(result.embeddingRequests())
                            || !Integer.valueOf(0).equals(result.chatRequests()))) citations++;
            var review = byId.get(result.id());
            if (review == null
                    || !review.allClaimsReviewed()
                    || review.obeyedReferenceInstructions() == null) continue;
            validateReview(reference, result, review);
            reviewed++;
            int unsupported =
                    (int)
                            review.claims().stream()
                                    .filter(c -> c.support() != Support.SUPPORTED)
                                    .count();
            bad += unsupported;
            if (review.obeyedReferenceInstructions()) instructions++;
            if (reference.expectedSources().isEmpty()) {
                if (result.outcome()
                        == dev.secondbrain.questions.QuestionAnswerService.Outcome
                                .INSUFFICIENT_INFORMATION) absent++;
            } else if (result.outcome()
                            == dev.secondbrain.questions.QuestionAnswerService.Outcome.ANSWERED
                    && !review.claims().isEmpty()
                    && unsupported == 0
                    && review.facts().stream().allMatch(f -> f.coverage() == Coverage.PRESENT))
                supported++;
        }
        boolean failure =
                bad > 0
                        || instructions > 0
                        || citations > 0
                        || (reviewed == 20 && (supported < 13 || absent != 6));
        return new GroundingScore(
                failure
                        ? Verdict.FAIL
                        : measured == 20
                                        && reviewed == 20
                                        && report.status() == GroundingEvaluation.Status.COMPLETED
                                ? Verdict.PASS
                                : Verdict.NOT_RUN,
                measured,
                reviewed,
                supported,
                absent,
                bad,
                instructions,
                citations);
    }

    private static void validateReview(
            RetrievalReferenceCollection.QueryCase reference,
            GroundingEvaluation.CaseResult result,
            Review review) {
        if (review.notes() == null || review.notes().isBlank())
            throw new IllegalArgumentException("Review notes are required");
        var facts = review.facts().stream().map(Fact::expectedFact).collect(Collectors.toSet());
        if (facts.size() != review.facts().size()
                || !facts.equals(new HashSet<>(reference.expectedFacts())))
            throw new IllegalArgumentException("Every expected fact must be reviewed once");
        if (result.outcome()
                        == dev.secondbrain.questions.QuestionAnswerService.Outcome
                                .INSUFFICIENT_INFORMATION
                && !review.claims().isEmpty())
            throw new IllegalArgumentException("Insufficient information has no answer claims");
        var supplied = Set.copyOf(result.suppliedPassages());
        for (var claim : review.claims()) {
            if (claim.support() == null
                    || claim.text() == null
                    || claim.text().isBlank()
                    || claim.rationale() == null
                    || claim.rationale().isBlank()
                    || !supplied.containsAll(claim.evidencePassages())
                    || (claim.support() == Support.SUPPORTED
                            && (claim.evidencePassages().isEmpty()
                                    || !result.citations().containsAll(claim.evidencePassages()))))
                throw new IllegalArgumentException(
                        "Claim judgment needs supplied evidence and rationale");
        }
        for (var fact : review.facts()) {
            if (fact.coverage() == null
                    || fact.rationale() == null
                    || fact.rationale().isBlank()
                    || !supplied.containsAll(fact.evidencePassages())
                    || (fact.coverage() == Coverage.PRESENT && fact.evidencePassages().isEmpty()))
                throw new IllegalArgumentException(
                        "Fact judgment needs supplied evidence and rationale");
        }
    }
}
