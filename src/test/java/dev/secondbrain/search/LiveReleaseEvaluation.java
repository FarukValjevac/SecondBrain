package dev.secondbrain.search;

import dev.secondbrain.documents.EvaluationDocuments;
import dev.secondbrain.embeddings.CloudflareEmbeddingProvider;
import dev.secondbrain.embeddings.CloudflareEmbeddingSettings;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingTelemetry;
import dev.secondbrain.questions.CloudflareChatProvider;
import dev.secondbrain.questions.CloudflareChatSettings;
import dev.secondbrain.questions.EvaluationQuestions;
import dev.secondbrain.questions.QuestionTelemetry;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Explicit two-phase release measurement against an owned disposable reference database. */
@Tag("live-ai")
class LiveReleaseEvaluation {
    @Test
    void evaluate() throws Exception {
        var settings = ReleaseEvaluationSettings.from(System.getProperties());
        ReleaseEvaluationArtifacts.verifyManifest();
        var index = ReferenceIndex.prepare(RetrievalReferenceCollection.loadRelease());
        EvaluationBudget.preflight(index, settings.embeddingsPerPhase());
        var embeddingSettings =
                new CloudflareEmbeddingSettings(
                        System.getenv("CLOUDFLARE_ACCOUNT_ID"),
                        System.getenv("CLOUDFLARE_API_TOKEN"),
                        new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        EvaluationBudget.PASSAGE_BATCH_SIZE);
        var chatSettings =
                new CloudflareChatSettings(
                        System.getenv("CLOUDFLARE_ACCOUNT_ID"),
                        System.getenv("CLOUDFLARE_API_TOKEN"),
                        CloudflareChatSettings.LLAMA_3_3_70B_ID,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30),
                        settings.chat().outputTokensPerRequest());
        var clock = Clock.systemUTC();
        Files.createDirectories(Path.of("evaluation-runs"));
        var directory =
                Files.createTempDirectory(Path.of("evaluation-runs"), "java-study-v2-release-");
        ReleaseEvaluationArtifacts.write(
                directory,
                "configuration.json",
                Map.of(
                        "gateVersion",
                        "release-gates-v1",
                        "manifestSha256",
                        ReleaseEvaluationArtifacts.MANIFEST_SHA256,
                        "authorization",
                        settings,
                        "chatModel",
                        chatSettings.model(),
                        "providerRevision",
                        "NOT_EXPOSED",
                        "context",
                        "5 whole passages; 8000 UTF-8 text bytes including question; no history",
                        "chatTimeoutSeconds",
                        30,
                        "limitations",
                        List.of(
                                "Eligibility confirmation is user supplied; no account billing or remaining neuron balance is measured.",
                                "Embedding budgets apply independently to each phase; grounding reuses stored corpus vectors but embeds queries again.",
                                "Text byte budgets exclude protocol and system prompt overhead; reported token ceilings are post-response and may overshoot once.",
                                "Missing usage is unknown; cancelled or failed attempts may still consume remote allowance.",
                                "Model IDs do not pin weights. Factual support requires human review of evidence.json; valid IDs do not establish truth.",
                                "Seeded read fixtures bypass ingestion; this small English corpus is not a long-document or multilingual benchmark.")));
        var retrieval = new RetrievalEvaluation(index, settings.embeddingsPerPhase(), clock);
        var grounding = new GroundingEvaluation(index, clock);
        var firstMeters = new SimpleMeterRegistry();
        var secondMeters = new SimpleMeterRegistry();
        try {
            var firstObservations = observations(firstMeters);
            var secondObservations = observations(secondMeters);
            var embeddingTelemetry = new EmbeddingTelemetry(firstMeters, firstObservations);
            var questionTelemetry = new QuestionTelemetry(secondMeters, secondObservations);
            try (var provider =
                            new CloudflareEmbeddingProvider(
                                    embeddingSettings, clock, embeddingTelemetry);
                    var answerEmbeddings =
                            new CloudflareEmbeddingProvider(
                                    embeddingSettings,
                                    clock,
                                    new EmbeddingTelemetry(secondMeters, secondObservations));
                    var chat = new CloudflareChatProvider(chatSettings, clock, questionTelemetry)) {
                var firstBudget =
                        new EvaluationBudget(provider, settings.embeddingsPerPhase(), firstMeters);
                var secondBudget =
                        new EvaluationBudget(
                                answerEmbeddings, settings.embeddingsPerPhase(), secondMeters);
                var chatBudget = new GroundingBudget(chat, settings.chat(), secondMeters);
                try (var postgres =
                        new PostgreSQLContainer(
                                DockerImageName.parse(RetrievalEvaluation.DATABASE_IMAGE)
                                        .asCompatibleSubstituteFor("postgres"))) {
                    save(
                            directory,
                            index,
                            retrieval,
                            grounding,
                            firstBudget,
                            secondBudget,
                            chatBudget);
                    postgres.start();
                    var source =
                            new DriverManagerDataSource(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var database = ReferenceDatabase.migrate(source);
                    database.seed(
                            index, firstBudget.embedReferencePassages(index), clock.instant());
                    retrieval.run(
                            search(database, firstBudget, firstMeters, firstObservations)::search,
                            labels -> database.applyDeletions(index, labels, clock.instant()),
                            firstBudget,
                            firstMeters);
                    if (retrieval.report(firstBudget.usage()).status()
                            == RetrievalEvaluation.Status.COMPLETED) {
                        // Independent retrieval is saved before generation; a quality miss is
                        // retained, not silently tuned away.
                        save(
                                directory,
                                index,
                                retrieval,
                                grounding,
                                firstBudget,
                                secondBudget,
                                chatBudget);
                        var answers =
                                EvaluationQuestions.answers(
                                        search(
                                                database,
                                                secondBudget,
                                                secondMeters,
                                                secondObservations),
                                        chatBudget,
                                        EvaluationDocuments.inspection(
                                                source, database.transactions()),
                                        questionTelemetry);
                        grounding.run(
                                answers::answer,
                                labels -> database.applyDeletions(index, labels, clock.instant()),
                                chatBudget,
                                secondBudget);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    retrieval.fail("INTERRUPTED", null);
                    grounding.fail("INTERRUPTED");
                } catch (dev.secondbrain.embeddings.EmbeddingFailure failure) {
                    retrieval.embeddingFailed(failure);
                } catch (EvaluationBudget.LimitReached exhausted) {
                    retrieval.fail("BUDGET_EXHAUSTED", null);
                } catch (RuntimeException failure) {
                    retrieval.fail("SETUP_OR_STORAGE_UNAVAILABLE", null);
                } finally {
                    save(
                            directory,
                            index,
                            retrieval,
                            grounding,
                            firstBudget,
                            secondBudget,
                            chatBudget);
                    ReleaseEvaluationArtifacts.reviewTemplate(directory, index);
                }
                System.out.println("Release evaluation artifacts: " + directory);
                if (retrieval.report(firstBudget.usage()).status()
                                != RetrievalEvaluation.Status.COMPLETED
                        || grounding.report(chatBudget.usage()).status()
                                != GroundingEvaluation.Status.COMPLETED)
                    throw new AssertionError(
                            "Incomplete evaluation; inspect ignored artifacts: " + directory);
                if (ReleaseQualityGates.retrieval(index, retrieval.report(firstBudget.usage()))
                                .verdict()
                        != ReleaseQualityGates.Verdict.PASS)
                    throw new AssertionError(
                            "Retrieval quality gates failed; grounding review remains independent");
            }
        } finally {
            firstMeters.close();
            secondMeters.close();
        }
    }

    private static ObservationRegistry observations(SimpleMeterRegistry meters) {
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        return registry;
    }

    private static SearchService search(
            ReferenceDatabase db,
            EvaluationBudget budget,
            SimpleMeterRegistry meters,
            ObservationRegistry observations) {
        return new SearchService(
                new QueryEmbedding(budget),
                new JdbcPassageSearch(db.jdbc(), db.transactions()),
                new SearchProperties(RetrievalEvaluation.MINIMUM_SCORE),
                new SearchTelemetry(meters, observations));
    }

    private static void save(
            Path directory,
            ReferenceIndex index,
            RetrievalEvaluation retrieval,
            GroundingEvaluation grounding,
            EvaluationBudget first,
            EvaluationBudget second,
            GroundingBudget chat)
            throws java.io.IOException {
        var report = retrieval.report(first.usage());
        ReleaseEvaluationArtifacts.write(directory, "retrieval.json", report);
        ReleaseEvaluationArtifacts.write(
                directory, "retrieval-score.json", ReleaseQualityGates.retrieval(index, report));
        ReleaseEvaluationArtifacts.write(
                directory, "grounding.json", grounding.report(chat.usage()));
        ReleaseEvaluationArtifacts.write(
                directory, "grounding-embedding-usage.json", second.usage());
        ReleaseEvaluationArtifacts.write(directory, "evidence.json", grounding.captures());
        ReleaseEvaluationArtifacts.write(
                directory,
                "grounding-score.json",
                ReleaseQualityGates.grounding(index, grounding.report(chat.usage()), List.of()));
    }
}
