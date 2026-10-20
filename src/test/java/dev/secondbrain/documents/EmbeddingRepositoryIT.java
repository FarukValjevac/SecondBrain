package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingVector;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Array;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Testcontainers
class EmbeddingRepositoryIT {
    private static final Instant AT = Instant.parse("2026-01-01T00:00:00.123456Z");
    private static final EmbeddingModel MODEL = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static JdbcIndexingRepository indexes;
    private static JdbcPassageRepository passages;
    private static JdbcDocumentRepository documents;
    private static JdbcExtractedTextRepository texts;
    private static JdbcEmbeddingRepository embeddings;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void migrateIsolatedPostgres() {
        var source = source();
        Flyway.configure().dataSource(source).load().migrate();
        var manager = new JdbcTransactionManager(source);
        jdbc = JdbcClient.create(source);
        indexes = new JdbcIndexingRepository(jdbc);
        passages = new JdbcPassageRepository(new JdbcTemplate(source), indexes, manager);
        documents = new JdbcDocumentRepository(jdbc);
        texts = new JdbcExtractedTextRepository(new JdbcTemplate(source), manager);
        embeddings = new JdbcEmbeddingRepository(jdbc, indexes, manager);
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
    }

    @AfterEach
    void clearOnlyContainerFixtures() {
        jdbc.sql("TRUNCATE documents CASCADE").update();
    }

    @Test
    void storesBoundedBatchesAndResumesWithTheSameValuesThroughANewRepository() throws Exception {
        var prepared = fixture(40000);
        var values = vectors(prepared);
        assertThat(values).hasSizeGreaterThan(32);
        var first = values.subList(0, 32);
        assertThat(insert(prepared, first)).isEqualTo(32);

        // New JDBC collaborators have no process-local knowledge of the earlier batch.
        var restartedSource = source();
        var manager = new JdbcTransactionManager(restartedSource);
        var restartedJdbc = JdbcClient.create(restartedSource);
        var restarted =
                new JdbcEmbeddingRepository(
                        restartedJdbc, new JdbcIndexingRepository(restartedJdbc), manager);
        var restartedTransaction = new TransactionTemplate(manager);
        restartedTransaction.setTimeout(5);
        var repository = restarted;
        int repeated =
                restartedTransaction.execute(
                        status -> repository.insertBatch(prepared.version(), MODEL, first));
        assertThat(repeated).isZero();
        int remaining =
                restartedTransaction.execute(
                        status ->
                                repository.insertBatch(
                                        prepared.version(),
                                        MODEL,
                                        values.subList(32, values.size())));
        assertThat(remaining).isEqualTo(values.size() - 32);
        assertThat(count()).isEqualTo(values.size());
        for (var value : values) assertRoundTrip(value);
        assertThat(
                        jdbc.sql(
                                        "SELECT embedding_model_id FROM document_index_versions WHERE document_id = :id")
                                .param("id", prepared.version().documentId())
                                .query(String.class)
                                .single())
                .isEqualTo(MODEL.id());
        assertThat(
                        jdbc.sql(
                                        "SELECT embedding_dimensions FROM document_index_versions WHERE document_id = :id")
                                .param("id", prepared.version().documentId())
                                .query(Integer.class)
                                .single())
                .isEqualTo(1024);
    }

    @Test
    void conflictingRetryRollsBackNewRowsAndPreservesTheFirstStoredVector() throws Exception {
        var prepared = fixture(3000);
        var values = vectors(prepared);
        insert(prepared, List.of(values.getFirst()));
        var changed = new PassageEmbedding(values.getFirst().passageId(), vector(99));
        assertThatThrownBy(() -> insert(prepared, List.of(values.get(1), changed)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count()).isEqualTo(1);
        assertRoundTrip(values.getFirst());
        assertThat(insert(prepared, List.of(values.get(1)))).isEqualTo(1);
    }

    @Test
    void callerRollbackAndMidBatchFailureRemoveRowsAndModelBindingAndAllowRetry() throws Exception {
        var prepared = fixture(3000);
        var values = vectors(prepared);
        assertThatThrownBy(() -> embeddings.insertBatch(prepared.version(), MODEL, values))
                .isInstanceOf(IllegalTransactionStateException.class);
        transaction.executeWithoutResult(
                status -> {
                    embeddings.insertBatch(prepared.version(), MODEL, values);
                    status.setRollbackOnly();
                });
        assertUnbound(prepared);
        jdbc.sql(
                        "ALTER TABLE document_embeddings ADD CONSTRAINT embedding_test_fault CHECK (passage_id <> '"
                                + values.get(1).passageId()
                                + "'::uuid) NOT VALID")
                .update();
        try {
            assertThatThrownBy(() -> insert(prepared, values))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertUnbound(prepared);
        } finally {
            jdbc.sql("ALTER TABLE document_embeddings DROP CONSTRAINT embedding_test_fault")
                    .update();
        }
        assertThat(insert(prepared, values)).isEqualTo(values.size());
    }

    @Test
    void concurrentBatchesKeepOneCopyOfEachVector() throws Exception {
        var prepared = fixture(3000);
        var values = vectors(prepared);
        var ready = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var tasks =
                    List.<Callable<Integer>>of(
                            () -> {
                                ready.await(5, TimeUnit.SECONDS);
                                return insert(prepared, values);
                            },
                            () -> {
                                ready.await(5, TimeUnit.SECONDS);
                                return insert(prepared, values);
                            });
            var results = workers.invokeAll(tasks, 15, TimeUnit.SECONDS);
            assertThat(
                            List.of(
                                    results.get(0).get(1, TimeUnit.SECONDS),
                                    results.get(1).get(1, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(values.size(), 0);
        }
        assertThat(count()).isEqualTo(values.size());
        for (var value : values) assertRoundTrip(value);
    }

    @Test
    void separatesIndexGenerationsAndDocumentsAndCascadesStagedVectors() throws Exception {
        var first = fixture(20);
        var other = fixture(20);
        insert(first, vectors(first));
        insert(other, vectors(other));
        var next =
                transaction.execute(
                        status ->
                                indexes.request(
                                                first.version().documentId(),
                                                OptionalLong.of(8),
                                                AT)
                                        .orElseThrow());
        var second = new PreparedPassages(next, 1, first.passages());
        transaction.executeWithoutResult(status -> passages.insert(second));
        insert(second, vectors(second));
        assertThat(count()).isEqualTo(3);
        assertThat(vectors(first).getFirst().passageId())
                .isNotEqualTo(vectors(second).getFirst().passageId());
        jdbc.sql(
                        "DELETE FROM document_index_versions WHERE document_id = :id AND indexing_version = 7")
                .param("id", first.version().documentId())
                .update();
        assertThat(count()).isEqualTo(2);
        jdbc.sql("DELETE FROM documents WHERE document_id = :id")
                .param("id", first.version().documentId())
                .update();
        assertThat(count()).isEqualTo(1);
        assertRoundTrip(vectors(other).getFirst());
    }

    @ParameterizedTest
    @EnumSource(Stale.class)
    void rejectsStaleOrDeletedGenerationsBeforeBindingAModel(Stale change) throws Exception {
        var prepared = fixture(20);
        var id = prepared.version().documentId();
        switch (change) {
            case REINDEX ->
                    transaction.executeWithoutResult(
                            status -> indexes.request(id, OptionalLong.of(8), AT));
            case SOURCE ->
                    jdbc.sql("UPDATE documents SET processing_version = 3 WHERE document_id = :id")
                            .param("id", id)
                            .update();
            case DELETED ->
                    jdbc.sql(
                                    "UPDATE documents SET deleted_at = CURRENT_TIMESTAMP WHERE document_id = :id")
                            .param("id", id)
                            .update();
            case REMOVED ->
                    jdbc.sql("DELETE FROM documents WHERE document_id = :id")
                            .param("id", id)
                            .update();
        }
        assertThatThrownBy(() -> insert(prepared, vectors(prepared)))
                .isInstanceOf(IllegalStateException.class);
        assertUnbound(prepared);
    }

    @Test
    void refusesToBindAModelBeforePassagesArePrepared() throws Exception {
        var prepared = fixture(20);
        var next =
                transaction.execute(
                        status ->
                                indexes.request(
                                                prepared.version().documentId(),
                                                OptionalLong.of(8),
                                                AT)
                                        .orElseThrow());
        var unprepared = new PreparedPassages(next, 1, prepared.passages());
        assertThatThrownBy(() -> insert(unprepared, vectors(unprepared)))
                .isInstanceOf(IllegalStateException.class);
        assertUnbound(unprepared);
    }

    @Test
    void rejectsEmptyOversizedOrDuplicateInputBeforeChangingMetadata() throws Exception {
        var prepared = fixture(40000);
        var values = vectors(prepared);
        for (var invalid :
                List.of(
                        List.<PassageEmbedding>of(),
                        values.subList(0, 33),
                        List.of(values.getFirst(), values.getFirst()))) {
            assertThatThrownBy(() -> insert(prepared, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
            assertUnbound(prepared);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrForeignPassagesRollBackEarlierRowsInTheBatch(boolean foreign) throws Exception {
        var prepared = fixture(3000);
        var id = foreign ? vectors(fixture(20)).getFirst().passageId() : UUID.randomUUID();
        var invalid = new PassageEmbedding(id, vector(2));
        assertThatThrownBy(() -> insert(prepared, List.of(vectors(prepared).getFirst(), invalid)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertUnbound(prepared);
    }

    @Test
    void sqlEnforcesDimensionsFiniteNonzeroValuesAndModelOwnership() throws Exception {
        var prepared = fixture(3000);
        var values = vectors(prepared);
        insert(prepared, List.of(values.getFirst()));
        for (var invalid :
                List.of(
                        "[1,2,3]",
                        "[" + "0,".repeat(1023) + "0]",
                        "[" + "0,".repeat(1023) + "NaN]",
                        "[" + "0,".repeat(1023) + "Infinity]")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.sql(
                                                    "UPDATE document_embeddings SET embedding = CAST(:value AS vector)")
                                            .param("value", invalid)
                                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        for (var update :
                List.of(
                        "embedding_model_id = 'other'",
                        "embedding_dimensions = 768",
                        "embedding_model_id = NULL",
                        "embedding_model_id = NULL, embedding_dimensions = NULL")) {
            assertThatThrownBy(
                            () -> jdbc.sql("UPDATE document_index_versions SET " + update).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        for (var update :
                List.of(
                        "model_id = 'other'",
                        "dimensions = 768",
                        "indexing_version = 99",
                        "passage_id = '" + UUID.randomUUID() + "'")) {
            assertThatThrownBy(() -> jdbc.sql("UPDATE document_embeddings SET " + update).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        // Other generation metadata is valid: only the composite passage ownership FK rejects this.
        var other = fixture(20);
        insert(other, vectors(other));
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "UPDATE document_embeddings SET document_id = :id WHERE passage_id = :passage")
                                        .param("id", other.version().documentId())
                                        .param("passage", values.getFirst().passageId())
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertRoundTrip(values.getFirst());
    }

    private static PreparedPassages fixture(int characters) throws Exception {
        var id = UUID.randomUUID();
        var text =
                new ExtractedText(
                        id, 2, AT, List.of(new ExtractedText.Line(1, "x".repeat(characters))));
        documents.insert(
                new DocumentMetadata(
                        id,
                        "fixture.txt",
                        DocumentType.TXT,
                        UUID.randomUUID(),
                        "a".repeat(64),
                        characters,
                        2,
                        ExtractionState.EXTRACTED,
                        AT,
                        AT));
        texts.insert(text);
        var version =
                transaction.execute(
                        status -> indexes.request(id, OptionalLong.of(7), AT).orElseThrow());
        var prepared = new PassageSplitter().split(version, text);
        transaction.executeWithoutResult(status -> passages.insert(prepared));
        return prepared;
    }

    private static List<PassageEmbedding> vectors(PreparedPassages prepared) {
        return prepared.passages().stream()
                .map(
                        passage ->
                                new PassageEmbedding(
                                        StoredPassage.from(prepared.version(), passage).passageId(),
                                        vector(passage.sequence())))
                .toList();
    }

    private static EmbeddingVector vector(int seed) {
        var values = new float[1024];
        values[0] = seed + 0.125f;
        values[1] = -1.5e-10f;
        values[1023] = Float.MIN_NORMAL;
        return new EmbeddingVector(MODEL, values);
    }

    private static int insert(PreparedPassages prepared, List<PassageEmbedding> values) {
        return transaction.execute(
                status -> embeddings.insertBatch(prepared.version(), MODEL, values));
    }

    private static long count() {
        return jdbc.sql("SELECT count(*) FROM document_embeddings").query(Long.class).single();
    }

    private static void assertUnbound(PreparedPassages prepared) {
        assertThat(count()).isZero();
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM document_index_versions WHERE document_id = :id AND indexing_version = :version AND embedding_model_id IS NOT NULL")
                                .param("id", prepared.version().documentId())
                                .param("version", prepared.version().number())
                                .query(Long.class)
                                .single())
                .isZero();
    }

    private static void assertRoundTrip(PassageEmbedding value) {
        float[] stored =
                jdbc.sql("SELECT embedding::real[] FROM document_embeddings WHERE passage_id = :id")
                        .param("id", value.passageId())
                        .query(
                                (row, index) -> {
                                    var array = row.getArray(1);
                                    try {
                                        Object raw = array.getArray();
                                        var components = new float[Array.getLength(raw)];
                                        for (int component = 0;
                                                component < components.length;
                                                component++) {
                                            components[component] =
                                                    ((Number) Array.get(raw, component))
                                                            .floatValue();
                                        }
                                        return components;
                                    } finally {
                                        array.free();
                                    }
                                })
                        .single();
        assertThat(stored).containsExactly(value.vector().values());
    }

    private static DriverManagerDataSource source() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    enum Stale {
        REINDEX,
        SOURCE,
        DELETED,
        REMOVED
    }
}
