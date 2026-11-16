package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.documents.EvaluationDocuments;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;
import dev.secondbrain.questions.ChatProvider;
import dev.secondbrain.questions.EvaluationQuestions;
import dev.secondbrain.questions.QuestionAnswerService;
import dev.secondbrain.questions.QuestionTelemetry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Real source revalidation over owned pgvector fixtures; no hosted inference or workers. */
@Testcontainers
class ReleaseGroundingIT {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse(RetrievalEvaluation.DATABASE_IMAGE)
                            .asCompatibleSubstituteFor("postgres"));

    @Test
    void evaluatesPublishedPdfEvidenceAndRechecksDeletionThroughTheProductionAnswerService()
            throws Exception {
        var index = ReferenceIndex.prepare(RetrievalReferenceCollection.loadRelease());
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var db = ReferenceDatabase.migrate(source);
        var model = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
        var values = new float[1024];
        values[0] = 1;
        var vector = new EmbeddingVector(model, values);
        db.seed(index, index.documents().stream().map(d -> vector).toList(), Instant.now());
        var pdf =
                index.documents().stream()
                        .filter(d -> d.type().equals("PDF"))
                        .findFirst()
                        .orElseThrow();
        var meters = new SimpleMeterRegistry();
        try {
            EmbeddingProvider provider =
                    new EmbeddingProvider() {
                        @Override
                        public EmbeddingModel model() {
                            return model;
                        }

                        @Override
                        public List<EmbeddingVector> embed(List<String> inputs) {
                            return inputs.stream().map(s -> vector).toList();
                        }
                    };
            var search =
                    new SearchService(
                            new QueryEmbedding(provider),
                            new JdbcPassageSearch(db.jdbc(), db.transactions()),
                            new SearchProperties(.3),
                            new SearchTelemetry(meters, ObservationRegistry.NOOP));
            var answers =
                    EvaluationQuestions.answers(
                            search,
                            r ->
                                    new ChatProvider.Reply(
                                            ChatProvider.Outcome.ANSWERED,
                                            "Controlled example, not a quality score.",
                                            List.of(r.evidence().getFirst().passageId())),
                            EvaluationDocuments.inspection(source, db.transactions()),
                            QuestionTelemetry.disabled());
            var query =
                    new SearchQuery(
                            "What score does the practice card require?",
                            new SearchScope.SelectedDocuments(Set.of(pdf.documentId())));
            var accepted = answers.answer(query);
            assertThat(accepted.outcome()).isEqualTo(QuestionAnswerService.Outcome.ANSWERED);
            assertThat(accepted.citations())
                    .singleElement()
                    .satisfies(
                            c -> {
                                assertThat(c.passageId()).isEqualTo(pdf.passageId());
                                assertThat(c.location().pageNumber()).hasValue(1);
                            });
            db.applyDeletions(index, List.of(pdf.label()), Instant.now());
            assertThat(answers.answer(query).outcome())
                    .isEqualTo(QuestionAnswerService.Outcome.INSUFFICIENT_INFORMATION);
        } finally {
            meters.close();
        }
    }
}
