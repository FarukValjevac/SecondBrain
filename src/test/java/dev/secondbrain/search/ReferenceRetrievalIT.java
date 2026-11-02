package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;
import dev.secondbrain.search.RetrievalReferenceCollection.Evidence;
import dev.secondbrain.search.RetrievalReferenceCollection.QueryCase;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Controlled vectors prove pgvector mechanics against gold fixtures, not language understanding.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ReferenceRetrievalIT {
    private static final EmbeddingModel MODEL = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
    private static final Instant AT = Instant.parse("2030-01-01T00:00:00.123456Z");
    private static final double MINIMUM_SCORE = 0.3;

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    private static ReferenceDatabase database;
    private static JdbcClient jdbc;
    private static JdbcTransactionManager transactions;
    private ReferenceIndex index;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();

    @BeforeAll
    static void migrateOnlyAnIsolatedDatabase() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        database = ReferenceDatabase.migrate(source);
        jdbc = database.jdbc();
        transactions = database.transactions();
    }

    @BeforeEach
    void seedCompletePublishedReferenceIndexes() throws IOException {
        index = ReferenceIndex.prepare(RetrievalReferenceCollection.load());
        observations
                .observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters));
        database.seed(
                index, List.of(vector(1, 0, 0, 0), vector(0, 1, 0, 0), vector(0, 0, 1, 0)), AT);
    }

    @AfterEach
    void clearOnlyTheIsolatedFixtureAndCloseMetrics() {
        try {
            jdbc.sql("TRUNCATE documents CASCADE").update();
        } finally {
            meters.close();
        }
    }

    static Stream<QueryCase> referenceCases() throws IOException {
        return RetrievalReferenceCollection.load().cases().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceCases")
    void retrievesExactlyTheExpectedVisiblePassages(QueryCase reference, CapturedOutput output) {
        applyDeletions(reference);
        var saved = storedVectors();
        var provider = new ScriptedQueryProvider(reference.query(), queryDirection(reference.id()));
        var query = new SearchQuery(reference.query(), index.scopeFor(reference.scope()));
        var result = service(provider).search(query);
        var expected = index.passagesForSources(reference.expectedSources());
        assertThat(result.matches())
                .extracting(SearchResult.Match::passageId)
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(result.matches()).hasSizeLessThanOrEqualTo(query.limit());
        if (reference.evidence() == Evidence.ABSENT) assertThat(result.matches()).isEmpty();
        for (var match : result.matches()) {
            var stored =
                    index.documents().stream()
                            .filter(document -> document.passageId().equals(match.passageId()))
                            .findFirst()
                            .orElseThrow();
            assertThat(match.documentId()).isEqualTo(stored.documentId());
            assertThat(match.indexingVersion()).isEqualTo(ReferenceIndex.INDEXING_VERSION);
            assertThat(match.sequence()).isEqualTo(1);
            assertThat(match.text()).isEqualTo(stored.text());
            assertThat(match.location()).isEqualTo(stored.location());
            assertThat(match.score()).isFinite().isBetween(MINIMUM_SCORE, 1.0);
            assertThat(reference.deletedDocuments()).doesNotContain(stored.label());
        }
        for (var fact : reference.expectedFacts())
            assertThat(result.matches())
                    .anySatisfy(match -> assertThat(match.text()).contains(fact));
        boolean empty =
                query.scope() instanceof SearchScope.SelectedDocuments selected
                        && selected.documentIds().isEmpty();
        assertThat(provider.calls).hasSize(empty ? 0 : 1);
        if (!empty) assertThat(provider.calls).containsExactly(List.of(reference.query()));
        assertThat(storedVectors()).isEqualTo(saved);
        assertNoInteractiveJobs();
        assertMeasurements(
                empty ? "empty_scope" : expected.isEmpty() ? "no_results" : "success",
                result.matches().size(),
                1);
        assertThat(output.getAll()).doesNotContain(reference.query());
        for (var document : index.documents())
            assertThat(output.getAll()).doesNotContain(document.name());
    }

    @Test
    void selectedScopeIsAppliedBeforeTheTopOneLimit() {
        var provider =
                new ScriptedQueryProvider("Are duplicate values allowed?", vector(1, 0.8f, 0, 0));
        var service = service(provider);
        assertThat(
                        service.search(
                                        new SearchQuery(
                                                "Are duplicate values allowed?",
                                                SearchScope.all(),
                                                1))
                                .matches())
                .extracting(SearchResult.Match::documentId)
                .containsExactly(index.document("arraylist").documentId());
        assertThat(
                        service.search(
                                        new SearchQuery(
                                                "Are duplicate values allowed?",
                                                SearchScope.selected(
                                                        Set.of(
                                                                index.document("hashset")
                                                                        .documentId())),
                                                1))
                                .matches())
                .extracting(SearchResult.Match::documentId)
                .containsExactly(index.document("hashset").documentId());
        assertThat(provider.calls).hasSize(2);
        assertMeasurements("success", 2, 2);
        assertNoInteractiveJobs();
    }

    @Test
    void aFreshReaderReusesPersistedVectorsAndTieOrdering() {
        var reference =
                index.collection().cases().stream()
                        .filter(query -> query.id().equals("duplicate-comparison"))
                        .findFirst()
                        .orElseThrow();
        var saved = storedVectors();
        var first = new ScriptedQueryProvider(reference.query(), queryDirection(reference.id()));
        var second = new ScriptedQueryProvider(reference.query(), queryDirection(reference.id()));
        var query = new SearchQuery(reference.query(), SearchScope.all());
        var original = service(first).search(query);
        // New real reader/transaction templates, same persisted indexes.
        var reopened = service(second).search(query);
        assertThat(original.matches()).hasSize(2);
        assertThat(reopened).isEqualTo(original);
        assertThat(first.calls).containsExactly(List.of(reference.query()));
        assertThat(second.calls).containsExactly(List.of(reference.query()));
        assertThat(storedVectors()).isEqualTo(saved);
        assertMeasurements("success", 4, 2);
        assertNoInteractiveJobs();
    }

    private SearchService service(EmbeddingProvider provider) {
        return new SearchService(
                new QueryEmbedding(provider),
                new JdbcPassageSearch(jdbc, transactions),
                new SearchProperties(MINIMUM_SCORE),
                new SearchTelemetry(meters, observations));
    }

    private void applyDeletions(QueryCase reference) {
        // Complete rows remain stored; each independent case gets only its declared tombstones.
        database.applyDeletions(index, reference.deletedDocuments(), AT);
    }

    private void assertMeasurements(String outcome, int totalResults, int calls) {
        var timer = meters.get("secondbrain.search.duration").tag("outcome", outcome).timer();
        assertThat(timer.count()).isEqualTo(calls);
        // Same whole-service observation boundary as interactive search; no performance threshold.
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isFinite().isGreaterThanOrEqualTo(0);
        var summary = meters.get("secondbrain.search.results").summary();
        assertThat(summary.count()).isEqualTo(calls);
        assertThat(summary.totalAmount()).isEqualTo(totalResults);
        assertThat(
                        meters.get("secondbrain.search.requests")
                                .tag("outcome", outcome)
                                .counter()
                                .count())
                .isEqualTo(calls);
        for (var meter : meters.getMeters())
            assertThat(meter.getId().getTags())
                    .allSatisfy(tag -> assertThat(tag.getKey()).isIn("outcome", "error"));
    }

    private static List<String> storedVectors() {
        return jdbc.sql(
                        """
                SELECT passage_id::text || ':' || document_id::text || ':' || indexing_version::text
                    || ':' || model_id || ':' || dimensions::text || ':' || embedding::text
                FROM document_embeddings ORDER BY passage_id
                """)
                .query(String.class)
                .list();
    }

    private static void assertNoInteractiveJobs() {
        assertThat(jdbc.sql("SELECT count(*) FROM event_keys").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM event_deliveries").query(Long.class).single())
                .isZero();
    }

    private static EmbeddingVector queryDirection(String caseId) {
        // Hand-assigned test directions, independent of expectedSources/expectedFacts. Not a
        // semantic model.
        return switch (caseId) {
            case "duplicates-direct",
                    "duplicates-paraphrase",
                    "removal-direct",
                    "wrong-scope",
                    "empty-selection",
                    "removed-arraylist" ->
                    vector(1, 0, 0, 0);
            case "duplicate-comparison" -> vector(1, 1, 0, 0);
            case "collections-and-review" -> vector(1, 0, 1, 0);
            case "threads-absent" -> vector(0, 0, 0, 1);
            case "hashset-filter" -> vector(1, 0.8f, 0, 0);
            case "hashset-survives" -> vector(0, 1, 0, 0);
            default ->
                    throw new IllegalArgumentException("No controlled vector for reference case");
        };
    }

    private static EmbeddingVector vector(float a, float b, float c, float d) {
        var values = new float[1024];
        values[0] = a;
        values[1] = b;
        values[2] = c;
        values[3] = d;
        return new EmbeddingVector(MODEL, values);
    }

    private static final class ScriptedQueryProvider implements EmbeddingProvider {
        private final String expected;
        private final EmbeddingVector direction;
        private final List<List<String>> calls = new ArrayList<>();

        private ScriptedQueryProvider(String expected, EmbeddingVector direction) {
            this.expected = expected;
            this.direction = direction;
        }

        @Override
        public EmbeddingModel model() {
            return MODEL;
        }

        @Override
        public List<EmbeddingVector> embed(List<String> inputs) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(inputs).containsExactly(expected);
            calls.add(List.copyOf(inputs));
            return List.of(direction);
        }
    }
}
