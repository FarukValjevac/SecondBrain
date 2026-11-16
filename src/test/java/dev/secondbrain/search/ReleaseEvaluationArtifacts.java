package dev.secondbrain.search;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Only explicit reference evaluation output belongs here; never application diagnostics. */
final class ReleaseEvaluationArtifacts {
    static final String MANIFEST_SHA256 =
            "04536def7b981dcf50476b7d51e8bc3cb3707c3695c1a5eb7115178211c8b308";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ReleaseEvaluationArtifacts() {}

    static void verifyManifest() throws IOException {
        try (var stream =
                ReleaseEvaluationArtifacts.class.getResourceAsStream(
                        "/retrieval/reference-v2/collection.json")) {
            if (stream == null) throw new IllegalArgumentException("Missing release manifest");
            try {
                var actual =
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(stream.readAllBytes()));
                if (!MANIFEST_SHA256.equals(actual))
                    throw new IllegalArgumentException(
                            "Release manifest changed; gates require an explicit new version");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    static void write(Path directory, String name, Object value) throws IOException {
        Files.writeString(
                directory.resolve(name),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    static <T> T read(Path directory, String name, Class<T> type) throws IOException {
        var path = directory.resolve(name);
        if (Files.size(path) > 2_000_000)
            throw new IllegalArgumentException("Oversized evaluation artifact");
        return JSON.readValue(Files.readString(path), type);
    }

    static String digest(Path directory) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String name : List.of("configuration.json", "grounding.json", "evidence.json"))
                digest.update(Files.readAllBytes(directory.resolve(name)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void reviewTemplate(Path directory, ReferenceIndex index) throws IOException {
        var cases = new ArrayList<ReleaseQualityGates.Review>();
        for (var reference : index.collection().cases()) {
            var facts =
                    reference.expectedFacts().stream()
                            .map(f -> new ReleaseQualityGates.Fact(f, null, List.of(), ""))
                            .toList();
            cases.add(
                    new ReleaseQualityGates.Review(
                            reference.id(), false, null, List.of(), facts, ""));
        }
        write(
                directory,
                "review.json",
                new ReleaseQualityGates.ReviewFile(digest(directory), "", cases));
    }

    static void review(Path directory, ReferenceIndex index) throws IOException {
        verifyManifest();
        var review = read(directory, "review.json", ReleaseQualityGates.ReviewFile.class);
        if (!digest(directory).equals(review.groundingSha256())
                || review.reviewer() == null
                || !review.reviewer().matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException(
                    "Review requires the matching artifacts and a safe reviewer label");
        var grounding = read(directory, "grounding.json", GroundingEvaluation.Report.class);
        var score = ReleaseQualityGates.grounding(index, grounding, review.cases());
        write(directory, "grounding-score.json", score);
        if (score.verdict() != ReleaseQualityGates.Verdict.PASS)
            throw new AssertionError("Grounding gates: " + score.verdict());
    }
}
