package dev.secondbrain.search;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/** Offline command: no Docker, credentials or model calls. */
@Tag("evaluation-review")
class GroundingReviewEvaluation {
    @Test
    void review() throws Exception {
        String run = System.getProperty("evaluation.run", "");
        var root = Path.of("evaluation-runs").toRealPath();
        var directory = Path.of(run).toRealPath();
        if (!directory.startsWith(root) || directory.equals(root))
            throw new IllegalArgumentException("Select a run under ignored evaluation-runs");
        ReleaseEvaluationArtifacts.review(
                directory, ReferenceIndex.prepare(RetrievalReferenceCollection.loadRelease()));
    }
}
