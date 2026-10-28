package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Real read projection and known vectors; no provider account, model call, worker or user file. */
@Testcontainers
class JdbcPassageSearchIT {
    private static final EmbeddingModel MODEL = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
    private static final Instant AT = Instant.parse("2030-01-01T00:00:00.123456Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static JdbcPassageSearch search;

    @BeforeAll
    static void migrateIsolatedDatabase() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = JdbcClient.create(source);
        search = new JdbcPassageSearch(jdbc, new JdbcTransactionManager(source));
    }

    @AfterEach
    void clearOnlyIsolatedDocuments() {
        jdbc.sql("TRUNCATE documents CASCADE").update();
    }

    @Test
    void finalReadIntersectsTheOriginalScopeAndKeepsCandidateOrder() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        fixture(a, 7, true, new float[][] {{1, 0}});
        fixture(b, 7, true, new float[][] {{0.8f, 0.6f}});
        var candidates =
                search.retrieve(new SearchQuery("Query", SearchScope.all()), vector(1, 0), 0.3);
        assertThat(candidates.matches()).hasSize(2);
        assertThat(
                        search.retainVisible(SearchScope.selected(Set.of(b)), MODEL, candidates)
                                .matches())
                .extracting(SearchResult.Match::documentId)
                .containsExactly(b);
        assertThat(
                        search.retainVisible(SearchScope.selected(Set.of()), MODEL, candidates)
                                .matches())
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(VisibilityChange.class)
    void committedChangesAfterRankingCannotEscapeTheFinalRead(VisibilityChange change)
            throws Exception {
        var changed = UUID.randomUUID();
        var survivor = UUID.randomUUID();
        fixture(changed, 7, true, new float[][] {{1, 0}});
        fixture(survivor, 7, true, new float[][] {{0.8f, 0.6f}});
        var ranked = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        var coordinated = spy(search);
        doAnswer(
                        call -> {
                            SearchResult candidates = call.getArgument(2);
                            assertThat(candidates.matches())
                                    .extracting(SearchResult.Match::documentId)
                                    .containsExactly(changed, survivor);
                            ranked.countDown();
                            assertThat(committed.await(10, TimeUnit.SECONDS)).isTrue();
                            return call.callRealMethod();
                        })
                .when(coordinated)
                .retainVisible(any(), any(), any());
        var provider = mock(EmbeddingProvider.class);
        when(provider.model()).thenReturn(MODEL);
        when(provider.embed(any())).thenReturn(List.of(vector(1, 0)));
        var service =
                new SearchService(
                        new QueryEmbedding(provider),
                        coordinated,
                        new SearchProperties(0.3),
                        SearchTelemetry.disabled());
        try (var thread = Executors.newSingleThreadExecutor()) {
            var response =
                    thread.submit(
                            () -> service.search(new SearchQuery("Query", SearchScope.all())));
            try {
                assertThat(ranked.await(10, TimeUnit.SECONDS)).isTrue();
                switch (change) {
                    case DELETED -> update(changed, "deleted_at = updated_at");
                    case REMOVED ->
                            jdbc.sql("DELETE FROM documents WHERE document_id = :id")
                                    .param("id", changed)
                                    .update();
                    case SOURCE_CHANGED -> update(changed, "processing_version = 2");
                    case REPLACED -> {
                        fixture(changed, 8, false, new float[][] {{1, 0}});
                        update(changed, "active_indexing_version = 8");
                    }
                    case INCOMPLETE ->
                            jdbc.sql("DELETE FROM document_embeddings WHERE document_id = :id")
                                    .param("id", changed)
                                    .update();
                }
            } finally {
                // Independent auto-commit writer finished before the fresh validation statement.
                committed.countDown();
            }
            var visible = response.get(10, TimeUnit.SECONDS);
            assertThat(visible.matches())
                    .extracting(SearchResult.Match::documentId)
                    .containsExactly(survivor);
            assertThat(visible.matches().getFirst().score()).isCloseTo(0.8, within(0.00001));
            verify(provider).embed(List.of("Query"));
        }
    }

    @Test
    void ranksByCosineWithStableTiesInclusiveThresholdBoundsAndLocations() {
        var a = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var b = UUID.fromString("10000000-0000-0000-0000-000000000002");
        fixture(a, 7, true, new float[][] {{1, 0}, {0.8f, 0.6f}, {0, 1}, {-1, 0}});
        fixture(b, 9, true, new float[][] {{1, 0}});
        var all = new SearchQuery("Synthetic query", SearchScope.all(), 20);
        var result = search.retrieve(all, vector(1, 0), -1);
        assertThat(result.matches())
                .extracting(SearchResult.Match::passageId)
                .containsExactly(
                        passage(a, 7, 1),
                        passage(b, 9, 1),
                        passage(a, 7, 2),
                        passage(a, 7, 3),
                        passage(a, 7, 4));
        assertThat(result.matches().get(2).score()).isCloseTo(0.8, within(0.00001));
        assertThat(result.matches().get(2).text()).isEqualTo("Synthetic café passage 2");
        assertThat(result.matches().get(2).indexingVersion()).isEqualTo(7);
        assertThat(result.matches().get(2).location().pageNumber()).hasValue(3);
        assertThat(result.matches().get(2).location().startLine()).isEqualTo(4);
        assertThat(search.retrieve(all, vector(1, 0), 1).matches()).hasSize(2);
        assertThat(
                        search.retrieve(
                                        new SearchQuery("Query", SearchScope.all(), 1),
                                        vector(1, 0),
                                        -1)
                                .matches())
                .extracting(SearchResult.Match::passageId)
                .containsExactly(passage(a, 7, 1));
        assertThat(search.retrieve(all, vector(1, 0), 0.81).matches()).hasSize(2);
    }

    @Test
    void undefinedCosineCannotBecomeATopMatchForExtremeFiniteVectors() {
        var id = UUID.randomUUID();
        fixture(id, 7, true, new float[][] {{Float.MAX_VALUE, 0}});
        assertThat(search.hasCompatibleIndex(SearchScope.all(), MODEL)).isTrue();
        // The pinned extension accumulates cosine products in float precision. An undefined
        // score must be filtered before ordering, rather than clamping NaN into a perfect match.
        assertThat(
                        search.retrieve(
                                        new SearchQuery("Query", SearchScope.all()),
                                        vector(Float.MAX_VALUE, 0),
                                        -1)
                                .matches())
                .isEmpty();
    }

    @Test
    void appliesSelectedDocumentsBeforeRankingAndLimitAndNeverExpandsAnEmptySelection() {
        var strongest = UUID.randomUUID();
        var selected = UUID.randomUUID();
        fixture(strongest, 7, true, new float[][] {{1, 0}});
        fixture(selected, 7, true, new float[][] {{0.8f, 0.6f}});
        update(selected, "document_type = 'TXT', original_name = 'synthetic.txt'");
        jdbc.sql("UPDATE document_passages SET page_number = NULL WHERE document_id = :id")
                .param("id", selected)
                .update();
        var query = new SearchQuery("Query", SearchScope.selected(Set.of(selected)), 1);
        assertThat(search.hasCompatibleIndex(query.scope(), MODEL)).isTrue();
        assertThat(search.retrieve(query, vector(1, 0), 0.3).matches())
                .extracting(SearchResult.Match::documentId)
                .containsExactly(selected);
        assertThat(
                        search.retrieve(query, vector(1, 0), 0.3)
                                .matches()
                                .getFirst()
                                .location()
                                .pageNumber())
                .isEmpty();
        var empty = SearchScope.selected(Set.of());
        assertThat(search.hasCompatibleIndex(empty, MODEL)).isFalse();
        assertThat(search.retrieve(new SearchQuery("Query", empty), vector(1, 0), -1).matches())
                .isEmpty();
        var absent = SearchScope.selected(Set.of(UUID.randomUUID()));
        assertThat(search.hasCompatibleIndex(absent, MODEL)).isFalse();
        assertThat(search.retrieve(new SearchQuery("Query", absent), vector(1, 0), -1).matches())
                .isEmpty();
    }

    @Test
    void searchesOnlyTheActiveGenerationAndKeepsItWhileADesiredReplacementStages() {
        var id = UUID.randomUUID();
        fixture(id, 7, true, new float[][] {{0.8f, 0.6f}});
        fixture(id, 8, false, new float[][] {{1, 0}});
        jdbc.sql("UPDATE documents SET desired_indexing_version = 8 WHERE document_id = :id")
                .param("id", id)
                .update();
        var query = new SearchQuery("Query", SearchScope.all());
        assertThat(search.retrieve(query, vector(1, 0), 0.3).matches())
                .extracting(SearchResult.Match::indexingVersion)
                .containsExactly(7L);
        jdbc.sql("UPDATE documents SET active_indexing_version = 8 WHERE document_id = :id")
                .param("id", id)
                .update();
        assertThat(search.retrieve(query, vector(1, 0), 0.3).matches())
                .extracting(SearchResult.Match::indexingVersion)
                .containsExactly(8L);
    }

    @ParameterizedTest
    @EnumSource(Excluded.class)
    void omitsHiddenStagedObsoleteUnsupportedAndIncompleteActiveGenerations(Excluded excluded) {
        var id = UUID.randomUUID();
        fixture(id, 7, true, new float[][] {{1, 0}, {1, 0}});
        switch (excluded) {
            case DELETED -> update(id, "deleted_at = updated_at");
            case QUEUED -> update(id, "extraction_state = 'QUEUED'");
            case PROCESSING -> update(id, "extraction_state = 'PROCESSING'");
            case FAILED -> update(id, "extraction_state = 'FAILED'");
            case SOURCE_CHANGED -> update(id, "processing_version = 2");
            case STAGED -> update(id, "active_indexing_version = NULL");
            case CONFIGURATION ->
                    jdbc.sql(
                                    "UPDATE document_index_versions SET passage_configuration_version = 99 WHERE document_id = :id")
                            .param("id", id)
                            .update();
            case MISSING_VECTOR ->
                    jdbc.sql("DELETE FROM document_embeddings WHERE passage_id = :id")
                            .param("id", passage(id, 7, 2))
                            .update();
            case MISSING_PASSAGE ->
                    jdbc.sql("DELETE FROM document_passages WHERE passage_id = :id")
                            .param("id", passage(id, 7, 2))
                            .update();
            case GAP ->
                    jdbc.sql(
                                    "UPDATE document_passages SET sequence_number = sequence_number + 10 WHERE document_id = :id")
                            .param("id", id)
                            .update();
            case UNBOUND -> {
                jdbc.sql("DELETE FROM document_embeddings WHERE document_id = :id")
                        .param("id", id)
                        .update();
                jdbc.sql(
                                "UPDATE document_index_versions SET embedding_model_id = NULL, embedding_dimensions = NULL WHERE document_id = :id")
                        .param("id", id)
                        .update();
            }
            case REMOVED ->
                    jdbc.sql("DELETE FROM documents WHERE document_id = :id")
                            .param("id", id)
                            .update();
        }
        assertThat(search.hasCompatibleIndex(SearchScope.all(), MODEL)).isFalse();
        assertThat(
                        search.retrieve(
                                        new SearchQuery("Query", SearchScope.all()),
                                        vector(1, 0),
                                        -1)
                                .matches())
                .isEmpty();
    }

    @Test
    void incompatibleModelOrNoIndexAvoidsInferenceButAnEligibleLowScoreReturnsNoMatches()
            throws Exception {
        var provider = mock(EmbeddingProvider.class);
        when(provider.model()).thenReturn(MODEL);
        var service =
                new SearchService(
                        new QueryEmbedding(provider),
                        search,
                        new SearchProperties(0.3),
                        SearchTelemetry.disabled());
        var query = new SearchQuery("Query", SearchScope.all());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.search(query))
                .isInstanceOfSatisfying(
                        SearchFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(SearchFailure.Reason.NO_COMPATIBLE_INDEX));
        verify(provider, never()).embed(any());
        var id = UUID.randomUUID();
        fixture(id, 7, true, new float[][] {{0, 1}});
        var incompatible = mock(EmbeddingModel.class);
        when(incompatible.id()).thenReturn("unsupported-model");
        when(incompatible.dimensions()).thenReturn(768);
        assertThat(search.hasCompatibleIndex(SearchScope.all(), incompatible)).isFalse();
        when(provider.embed(any())).thenReturn(List.of(vector(1, 0)));
        assertThat(service.search(query).matches()).isEmpty();
        verify(provider).embed(List.of("Query"));
    }

    @Test
    void deletionCommittedDuringInferenceIsExcludedByTheRankingRead() throws Exception {
        var id = UUID.randomUUID();
        fixture(id, 7, true, new float[][] {{1, 0}});
        var provider = mock(EmbeddingProvider.class);
        when(provider.model()).thenReturn(MODEL);
        when(provider.embed(any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            update(id, "deleted_at = updated_at");
                            return List.of(vector(1, 0));
                        });
        var service =
                new SearchService(
                        new QueryEmbedding(provider),
                        search,
                        new SearchProperties(0.3),
                        SearchTelemetry.disabled());
        assertThat(service.search(new SearchQuery("Query", SearchScope.all())).matches()).isEmpty();
    }

    private static void fixture(UUID id, long generation, boolean active, float[][] directions) {
        jdbc.sql(
                        """
                INSERT INTO documents(document_id, original_name, document_type, storage_key, content_sha256,
                    original_size_bytes, processing_version, extraction_state, created_at, updated_at)
                VALUES (:id, 'synthetic.pdf', 'PDF', :storage, :hash, 64, 1, 'EXTRACTED', :at, :at)
                ON CONFLICT (document_id) DO NOTHING
                """)
                .param("id", id)
                .param("storage", UUID.randomUUID())
                .param("hash", "a".repeat(64))
                .param("at", Timestamp.from(AT))
                .update();
        jdbc.sql(
                        "INSERT INTO document_extractions(document_id, processing_version, extracted_at) VALUES (:id, 1, :at) ON CONFLICT DO NOTHING")
                .param("id", id)
                .param("at", Timestamp.from(AT))
                .update();
        jdbc.sql(
                        """
                INSERT INTO document_index_versions(document_id, indexing_version, extraction_version, created_at,
                    passage_configuration_version, passage_count, embedding_model_id, embedding_dimensions)
                VALUES (:id, :generation, 1, :at, 1, :count, :model, 1024)
                """)
                .param("id", id)
                .param("generation", generation)
                .param("at", Timestamp.from(AT))
                .param("count", directions.length)
                .param("model", MODEL.id())
                .update();
        for (int index = 0; index < directions.length; index++) {
            int sequence = index + 1;
            var passage = passage(id, generation, sequence);
            var text = "Synthetic café passage " + sequence;
            jdbc.sql(
                            """
                    INSERT INTO document_passages(passage_id, document_id, indexing_version, sequence_number,
                        passage_text, start_line, start_column, end_line, end_column, page_number)
                    VALUES (:passage, :id, :generation, :sequence, :text, :line, 0, :line, :end, :page)
                    """)
                    .param("passage", passage)
                    .param("id", id)
                    .param("generation", generation)
                    .param("sequence", sequence)
                    .param("text", text)
                    .param("line", sequence * 2)
                    .param("end", text.length())
                    .param("page", sequence == 2 ? 3 : 1)
                    .update();
            var values = new StringBuilder("[");
            for (int component = 0; component < 1024; component++) {
                if (component > 0) values.append(',');
                values.append(
                        component < directions[index].length ? directions[index][component] : 0);
            }
            values.append(']');
            jdbc.sql(
                            "INSERT INTO document_embeddings(passage_id, document_id, indexing_version, model_id, dimensions, embedding) VALUES (:passage, :id, :generation, :model, 1024, CAST(:vector AS vector(1024)))")
                    .param("passage", passage)
                    .param("id", id)
                    .param("generation", generation)
                    .param("model", MODEL.id())
                    .param("vector", values.toString())
                    .update();
        }
        if (active) update(id, "active_indexing_version = " + generation);
    }

    private static UUID passage(UUID id, long generation, int sequence) {
        return UUID.nameUUIDFromBytes(
                ("secondbrain:passage:v1:" + id + ":" + generation + ":" + sequence)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static void update(UUID id, String assignment) {
        // Assignments are fixed fixture constants, never query or document input.
        jdbc.sql("UPDATE documents SET " + assignment + " WHERE document_id = :id")
                .param("id", id)
                .update();
    }

    private static EmbeddingVector vector(float x, float y) {
        var values = new float[1024];
        values[0] = x;
        values[1] = y;
        return new EmbeddingVector(MODEL, values);
    }

    enum VisibilityChange {
        DELETED,
        REMOVED,
        SOURCE_CHANGED,
        REPLACED,
        INCOMPLETE
    }

    enum Excluded {
        DELETED,
        QUEUED,
        PROCESSING,
        FAILED,
        SOURCE_CHANGED,
        STAGED,
        CONFIGURATION,
        MISSING_VECTOR,
        MISSING_PASSAGE,
        GAP,
        UNBOUND,
        REMOVED
    }
}
