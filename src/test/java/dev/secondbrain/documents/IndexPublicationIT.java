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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Publication storage prerequisites only: no live inference, pollers, or publication events. */
@Testcontainers
class IndexPublicationIT {
    private static final Instant AT = Instant.parse("2030-01-01T00:00:00.123456Z");
    private static final EmbeddingModel MODEL = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static JdbcDocumentRepository documents;
    private static JdbcExtractedTextRepository texts;
    private static JdbcIndexingRepository indexes;
    private static JdbcPassageRepository passages;
    private static JdbcEmbeddingRepository embeddings;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrateIsolatedDatabase() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        var manager = new JdbcTransactionManager(source);
        jdbc = JdbcClient.create(source);
        documents = new JdbcDocumentRepository(jdbc);
        texts = new JdbcExtractedTextRepository(new JdbcTemplate(source), manager);
        indexes = new JdbcIndexingRepository(jdbc);
        passages = new JdbcPassageRepository(new JdbcTemplate(source), indexes, manager);
        embeddings = new JdbcEmbeddingRepository(jdbc, indexes, manager);
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(5);
    }

    @AfterEach
    void clearOnlyContainerFixtures() {
        // Event keys deliberately outlive document ownership; clear both isolated fixture roots.
        jdbc.sql("TRUNCATE documents, event_keys CASCADE").update();
    }

    @Test
    void activatesOnlyACompleteGenerationAndRepeatsWithoutChangingItsTimestamp() throws Exception {
        var prepared = fixture(7);
        assertThat(active(prepared.version().documentId())).isEmpty();
        assertThat(activate(prepared.version()))
                .isEqualTo(JdbcIndexingRepository.Activation.INCOMPLETE);
        storeVectors(prepared);
        assertThat(activate(prepared.version()))
                .isEqualTo(JdbcIndexingRepository.Activation.ACTIVATED);
        assertThat(active(prepared.version().documentId())).hasValue(7);
        var updated = updatedAt(prepared.version().documentId());
        var repeated =
                transactions.execute(
                        status -> indexes.activate(prepared.version(), MODEL, AT.plusSeconds(60)));
        assertThat(repeated).isEqualTo(JdbcIndexingRepository.Activation.ALREADY_ACTIVE);
        assertThat(updatedAt(prepared.version().documentId())).isEqualTo(updated);
        assertThat(jdbc.sql("SELECT count(*) FROM domain_events").query(Long.class).single())
                .isZero();
    }

    @ParameterizedTest
    @EnumSource(Incomplete.class)
    void rejectsIncompleteOrIncompatibleStagingWithoutSelectingAnActiveVersion(Incomplete condition)
            throws Exception {
        var prepared = fixture(7);
        var version = prepared.version();
        if (condition == Incomplete.PARTIAL_BATCH) {
            transactions.executeWithoutResult(
                    status ->
                            embeddings.insertBatch(
                                    version, MODEL, vectors(prepared).subList(0, 1)));
        } else if (condition != Incomplete.UNPREPARED) {
            storeVectors(prepared);
        }
        switch (condition) {
            case PARTIAL_BATCH -> {}
            case MISSING_PASSAGE ->
                    jdbc.sql(
                                    "DELETE FROM document_passages WHERE document_id = :id AND sequence_number = 2")
                            .param("id", version.documentId())
                            .update();
            case MISSING_VECTOR ->
                    jdbc.sql("DELETE FROM document_embeddings WHERE passage_id = :id")
                            .param("id", vectors(prepared).getFirst().passageId())
                            .update();
            case COUNT_MISMATCH ->
                    jdbc.sql(
                                    "UPDATE document_index_versions SET passage_count = passage_count + 1 WHERE document_id = :id")
                            .param("id", version.documentId())
                            .update();
            case SEQUENCE_GAP ->
                    jdbc.sql(
                                    "UPDATE document_passages SET sequence_number = sequence_number + 100 WHERE document_id = :id")
                            .param("id", version.documentId())
                            .update();
            case UNKNOWN_CONFIGURATION ->
                    jdbc.sql(
                                    "UPDATE document_index_versions SET passage_configuration_version = 2 WHERE document_id = :id")
                            .param("id", version.documentId())
                            .update();
            case UNPREPARED ->
                    jdbc.sql(
                                    """
                    UPDATE document_index_versions SET passage_configuration_version = NULL, passage_count = NULL
                    WHERE document_id = :id
                    """)
                            .param("id", version.documentId())
                            .update();
        }
        assertThat(activate(version)).isEqualTo(JdbcIndexingRepository.Activation.INCOMPLETE);
        assertThat(active(version.documentId())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Ineligible.class)
    void rejectsObsoleteSourceDesiredOrHiddenDocuments(Ineligible condition) throws Exception {
        var prepared = fixture(7);
        storeVectors(prepared);
        var version = prepared.version();
        switch (condition) {
            case DELETED ->
                    jdbc.sql("UPDATE documents SET deleted_at = updated_at WHERE document_id = :id")
                            .param("id", version.documentId())
                            .update();
            case UNEXTRACTED ->
                    jdbc.sql(
                                    "UPDATE documents SET extraction_state = 'QUEUED' WHERE document_id = :id")
                            .param("id", version.documentId())
                            .update();
            case SOURCE_CHANGED ->
                    jdbc.sql("UPDATE documents SET processing_version = 3 WHERE document_id = :id")
                            .param("id", version.documentId())
                            .update();
            case NEWER_DESIRED ->
                    transactions.executeWithoutResult(
                            status ->
                                    indexes.request(version.documentId(), OptionalLong.of(8), AT));
            case REMOVED ->
                    jdbc.sql("DELETE FROM documents WHERE document_id = :id")
                            .param("id", version.documentId())
                            .update();
            case WRONG_SOURCE_ARGUMENT -> {}
        }
        var requested =
                condition == Ineligible.WRONG_SOURCE_ARGUMENT
                        ? new IndexingVersion(version.documentId(), version.number(), 1, AT)
                        : version;
        assertThat(activate(requested)).isEqualTo(JdbcIndexingRepository.Activation.INELIGIBLE);
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM documents WHERE active_indexing_version IS NOT NULL")
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void requiresACallerTransactionAndRollsBackItsActivePointerAndTimestamp() throws Exception {
        var prepared = fixture(7);
        storeVectors(prepared);
        assertThatThrownBy(() -> indexes.activate(prepared.version(), MODEL, AT))
                .isInstanceOf(IllegalStateException.class);
        var before = updatedAt(prepared.version().documentId());
        transactions.executeWithoutResult(
                status -> {
                    assertThat(indexes.activate(prepared.version(), MODEL, AT.plusSeconds(10)))
                            .isEqualTo(JdbcIndexingRepository.Activation.ACTIVATED);
                    status.setRollbackOnly();
                });
        assertThat(active(prepared.version().documentId())).isEmpty();
        assertThat(updatedAt(prepared.version().documentId())).isEqualTo(before);
    }

    @Test
    void preservesTheOldActiveVersionUntilItsCompleteReplacementCanBeSelected() throws Exception {
        var first = fixture(7);
        storeVectors(first);
        assertThat(activate(first.version()))
                .isEqualTo(JdbcIndexingRepository.Activation.ACTIVATED);
        var id = first.version().documentId();
        var next =
                transactions.execute(
                        status -> indexes.request(id, OptionalLong.of(8), AT).orElseThrow());
        var prepared =
                new PassageSplitter().split(next, texts.findByDocumentVersion(id, 2).orElseThrow());
        transactions.executeWithoutResult(status -> passages.insert(prepared));
        assertThat(activate(next)).isEqualTo(JdbcIndexingRepository.Activation.INCOMPLETE);
        assertThat(active(id)).hasValue(7);
        assertThat(activate(first.version()))
                .isEqualTo(JdbcIndexingRepository.Activation.INELIGIBLE);
        storeVectors(prepared);
        assertThat(activate(next)).isEqualTo(JdbcIndexingRepository.Activation.ACTIVATED);
        assertThat(active(id)).hasValue(8);
        assertThat(activate(first.version()))
                .isEqualTo(JdbcIndexingRepository.Activation.INELIGIBLE);
        assertThat(active(id)).hasValue(8);
    }

    @Test
    void enforcesSameDocumentOwnershipModelDimensionsAndSafeCascadeDeletion() throws Exception {
        var prepared = fixture(7);
        storeVectors(prepared);
        var other = fixture(8);
        var id = prepared.version().documentId();
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "UPDATE documents SET active_indexing_version = 8 WHERE document_id = :id")
                                        .param("id", id)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "UPDATE documents SET active_indexing_version = 0 WHERE document_id = :id")
                                        .param("id", id)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        for (var assignment : List.of("model_id = 'other'", "dimensions = 768")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.sql(
                                                    "UPDATE document_embeddings SET "
                                                            + assignment
                                                            + " WHERE document_id = :id")
                                            .param("id", id)
                                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(active(id)).isEmpty();
        assertThat(activate(prepared.version()))
                .isEqualTo(JdbcIndexingRepository.Activation.ACTIVATED);
        jdbc.sql("UPDATE documents SET desired_indexing_version = NULL WHERE document_id = :id")
                .param("id", id)
                .update();
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "DELETE FROM document_index_versions WHERE document_id = :id")
                                        .param("id", id)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql("DELETE FROM documents WHERE document_id = :id").param("id", id).update();
        assertThat(
                        jdbc.sql("SELECT count(*) FROM document_embeddings WHERE document_id = :id")
                                .param("id", id)
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(
                        jdbc.sql("SELECT count(*) FROM document_passages WHERE document_id = :id")
                                .param("id", id)
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(active(other.version().documentId())).isEmpty();
    }

    @Test
    void competingTransactionsSelectOneActiveVersionAndOneIdempotentRepeat() throws Exception {
        var prepared = fixture(7);
        storeVectors(prepared);
        var barrier = new CyclicBarrier(2);
        Callable<JdbcIndexingRepository.Activation> activate =
                () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return activate(prepared.version());
                };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(activate);
            var two = pool.submit(activate);
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            JdbcIndexingRepository.Activation.ACTIVATED,
                            JdbcIndexingRepository.Activation.ALREADY_ACTIVE);
        }
        assertThat(active(prepared.version().documentId())).hasValue(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING", "FAILED"})
    void recoveryNeverBypassesAnExistingEmbeddingDeliveryBudget(String state) throws Exception {
        var prepared = fixture(7);
        storeVectors(prepared);
        var event = UUID.randomUUID();
        jdbc.sql(
                        """
                INSERT INTO event_keys(event_id, document_id, processing_version, event_type, schema_version, fingerprint)
                VALUES (:event, :id, 7, 'PassagesPrepared', 1, :fingerprint)
                """)
                .param("event", event)
                .param("id", prepared.version().documentId())
                .param("fingerprint", "a".repeat(64))
                .update();
        jdbc.sql(
                        """
                INSERT INTO domain_events(event_id, occurred_at, correlation_id, reference_ids, created_at)
                VALUES (:event, :at, :correlation, '{}'::jsonb, :at)
                """)
                .param("event", event)
                .param("at", Timestamp.from(AT))
                .param("correlation", UUID.randomUUID())
                .update();
        jdbc.sql(
                        """
                INSERT INTO event_deliveries(delivery_id, event_id, handler_id, state, attempts, eligible_at,
                    lease_owner, lease_token, lease_until, deadline_at, created_at, updated_at)
                VALUES (:delivery, :event, 'documents.embed-passages.v1', :state, 5, :at,
                    CASE WHEN :state = 'RUNNING' THEN gen_random_uuid() END,
                    CASE WHEN :state = 'RUNNING' THEN gen_random_uuid() END,
                    CASE WHEN :state = 'RUNNING' THEN CAST(:until AS timestamptz) END,
                    CASE WHEN :state = 'RUNNING' THEN CAST(:until AS timestamptz) END, :at, :at)
                """)
                .param("delivery", UUID.randomUUID())
                .param("event", event)
                .param("state", state)
                .param("at", Timestamp.from(AT))
                .param("until", Timestamp.from(AT.plusSeconds(60)))
                .update();
        assertThat(indexes.findPublicationRecovery(MODEL, 100)).isEmpty();
        assertThat(indexes.canRecoverPublication(prepared.version(), MODEL)).isFalse();
        assertThat(indexes.completedEmbeddingSource(prepared.version(), event)).isFalse();
        assertThat(
                        jdbc.sql("SELECT attempts FROM event_deliveries WHERE event_id = :event")
                                .param("event", event)
                                .query(Integer.class)
                                .single())
                .isEqualTo(5);
    }

    private static PreparedPassages fixture(long number) throws Exception {
        var id = UUID.randomUUID();
        var text =
                new ExtractedText(
                        id,
                        2,
                        AT,
                        List.of(new ExtractedText.Line(1, "Synthetic note. ".repeat(200))));
        documents.insert(
                new DocumentMetadata(
                        id,
                        "synthetic.txt",
                        DocumentType.TXT,
                        UUID.randomUUID(),
                        "a".repeat(64),
                        text.lines().getFirst().text().length(),
                        2,
                        ExtractionState.EXTRACTED,
                        AT,
                        AT));
        texts.insert(text);
        var version =
                transactions.execute(
                        status -> indexes.request(id, OptionalLong.of(number), AT).orElseThrow());
        var prepared = new PassageSplitter().split(version, text);
        transactions.executeWithoutResult(status -> passages.insert(prepared));
        assertThat(prepared.passages()).hasSizeGreaterThan(1);
        return prepared;
    }

    private static List<PassageEmbedding> vectors(PreparedPassages prepared) {
        return prepared.passages().stream()
                .map(
                        passage -> {
                            float[] components = new float[1024];
                            components[0] = passage.sequence();
                            return new PassageEmbedding(
                                    StoredPassage.from(prepared.version(), passage).passageId(),
                                    new EmbeddingVector(MODEL, components));
                        })
                .toList();
    }

    private static void storeVectors(PreparedPassages prepared) {
        transactions.executeWithoutResult(
                status -> embeddings.insertBatch(prepared.version(), MODEL, vectors(prepared)));
    }

    private static JdbcIndexingRepository.Activation activate(IndexingVersion version) {
        return transactions.execute(status -> indexes.activate(version, MODEL, AT.plusSeconds(10)));
    }

    private static OptionalLong active(UUID id) {
        return jdbc.sql("SELECT active_indexing_version FROM documents WHERE document_id = :id")
                .param("id", id)
                .query(
                        (row, index) -> {
                            var value = row.getObject("active_indexing_version", Long.class);
                            return value == null ? OptionalLong.empty() : OptionalLong.of(value);
                        })
                .single();
    }

    private static Instant updatedAt(UUID id) {
        return jdbc.sql("SELECT updated_at FROM documents WHERE document_id = :id")
                .param("id", id)
                .query(Timestamp.class)
                .single()
                .toInstant();
    }

    enum Incomplete {
        PARTIAL_BATCH,
        MISSING_PASSAGE,
        MISSING_VECTOR,
        COUNT_MISMATCH,
        SEQUENCE_GAP,
        UNKNOWN_CONFIGURATION,
        UNPREPARED
    }

    enum Ineligible {
        DELETED,
        UNEXTRACTED,
        SOURCE_CHANGED,
        NEWER_DESIRED,
        REMOVED,
        WRONG_SOURCE_ARGUMENT
    }
}
