package dev.secondbrain.search;

import dev.secondbrain.embeddings.CloudflareEmbeddingProvider;
import dev.secondbrain.embeddings.CloudflareEmbeddingSettings;
import dev.secondbrain.embeddings.EmbeddingFailure;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingTelemetry;

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

/**
 * Invoked only by the explicit Maven evaluation profile, never application startup/default tests.
 */
@Tag("live-ai")
class LiveRetrievalEvaluation {
    @Test
    void evaluateReferenceRetrieval() throws Exception {
        // Validate consent, budgets, fixtures and credentials before Docker or inference.
        var limits = EvaluationBudget.Limits.from(System.getProperties());
        var index = ReferenceIndex.prepare(RetrievalReferenceCollection.load());
        EvaluationBudget.preflight(index, limits);
        var settings =
                new CloudflareEmbeddingSettings(
                        System.getenv("CLOUDFLARE_ACCOUNT_ID"),
                        System.getenv("CLOUDFLARE_API_TOKEN"),
                        new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        3);
        var clock = Clock.systemUTC();
        var evaluation = new RetrievalEvaluation(index, limits, clock);
        var root = Path.of("evaluation-runs");
        Files.createDirectories(root);
        var path = Files.createTempDirectory(root, "java-study-v1-live-").resolve("report.json");
        var meters = new SimpleMeterRegistry();
        var observations = ObservationRegistry.create();
        observations
                .observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters));
        EvaluationBudget budget = null;
        var emptyUsage = new EvaluationBudget.Usage(0, 0, 0, 0, 0);
        try (var postgres =
                new PostgreSQLContainer(
                        DockerImageName.parse(RetrievalEvaluation.DATABASE_IMAGE)
                                .asCompatibleSubstituteFor("postgres"))) {
            RetrievalEvaluation.write(path, evaluation.report(emptyUsage));
            postgres.start();
            var database =
                    ReferenceDatabase.migrate(
                            new DriverManagerDataSource(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword()));
            try (var provider =
                    new CloudflareEmbeddingProvider(
                            settings, clock, new EmbeddingTelemetry(meters, observations))) {
                budget = new EvaluationBudget(provider, limits, meters);
                var vectors =
                        budget.embed(
                                index.documents().stream()
                                        .map(ReferenceIndex.Document::text)
                                        .toList());
                database.seed(index, vectors, clock.instant());
                var service =
                        new SearchService(
                                new QueryEmbedding(budget),
                                new JdbcPassageSearch(database.jdbc(), database.transactions()),
                                new SearchProperties(RetrievalEvaluation.MINIMUM_SCORE),
                                new SearchTelemetry(meters, observations));
                evaluation.run(
                        service::search,
                        labels -> database.applyDeletions(index, labels, clock.instant()),
                        budget,
                        meters);
            }
        } catch (EmbeddingFailure failed) {
            evaluation.embeddingFailed(failed);
        } catch (EvaluationBudget.LimitReached exhausted) {
            evaluation.fail("BUDGET_EXHAUSTED", null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            evaluation.fail("INTERRUPTED", null);
        } catch (RuntimeException unavailable) {
            evaluation.fail("SETUP_OR_STORAGE_UNAVAILABLE", null);
        } finally {
            try {
                RetrievalEvaluation.write(
                        path, evaluation.report(budget == null ? emptyUsage : budget.usage()));
            } finally {
                meters.close();
            }
        }
        var report = evaluation.report(budget == null ? emptyUsage : budget.usage());
        System.out.println("Retrieval evaluation " + report.status() + "; report: " + path);
        if (report.status() != RetrievalEvaluation.Status.COMPLETED)
            throw new AssertionError(
                    "Retrieval evaluation stopped: " + report.failureCode() + "; see " + path);
    }
}
