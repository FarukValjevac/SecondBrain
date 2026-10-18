package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Testcontainers
class PassageRepositoryIT {
    private static final Instant AT = Instant.parse("2026-01-01T00:00:00.123456Z");

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
    private static TransactionTemplate transaction;

    @BeforeAll
    static void migrateOnlyTheIsolatedDatabase() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        var manager = new JdbcTransactionManager(source);
        var template = new JdbcTemplate(source);
        jdbc = JdbcClient.create(source);
        indexes = new JdbcIndexingRepository(jdbc);
        passages = new JdbcPassageRepository(template, indexes, manager);
        documents = new JdbcDocumentRepository(jdbc);
        texts = new JdbcExtractedTextRepository(template, manager);
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
    }

    @AfterEach
    void clearContainerFixtures() {
        jdbc.sql("TRUNCATE documents CASCADE").update();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void roundTripsBatchesUnicodeLocationsAndStableIdsOnDuplicatePreparation(boolean paged)
            throws Exception {
        var prepared = fixture(paged, "é📚".repeat(102500));
        assertThat(prepared.passages()).hasSizeGreaterThan(200);
        assertThat(transaction.<Boolean>execute(status -> passages.insert(prepared))).isTrue();
        var stored = passages.findByDocumentVersion(prepared.version().documentId(), 7);
        assertThat(stored).containsExactlyElementsOf(expected(prepared));
        assertThat(transaction.<Boolean>execute(status -> passages.insert(prepared))).isFalse();
        assertThat(passages.findByDocumentVersion(prepared.version().documentId(), 7))
                .isEqualTo(stored);
        assertThatThrownBy(() -> stored.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(
                        jdbc.sql(
                                        "SELECT passage_configuration_version FROM document_index_versions WHERE document_id = :id")
                                .param("id", prepared.version().documentId())
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void separatesDocumentsAndVersionsAndCascadesAllStagedContentOnDeletion() throws Exception {
        var first = fixture(false, "Note");
        transaction.executeWithoutResult(status -> passages.insert(first));
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
        var other = fixture(true, "Other");
        transaction.executeWithoutResult(status -> passages.insert(other));
        assertThat(passages.findByDocumentVersion(first.version().documentId(), 7))
                .isEqualTo(expected(first));
        assertThat(passages.findByDocumentVersion(first.version().documentId(), 8))
                .isEqualTo(expected(second));
        assertThat(expected(first))
                .extracting(StoredPassage::passageId)
                .doesNotContainAnyElementsOf(
                        expected(second).stream().map(StoredPassage::passageId).toList());
        assertThat(passages.findByDocumentVersion(UUID.randomUUID(), 7)).isEmpty();
        jdbc.sql("DELETE FROM documents WHERE document_id = :id")
                .param("id", first.version().documentId())
                .update();
        assertThat(passages.findByDocumentVersion(first.version().documentId(), 7)).isEmpty();
        assertThat(passages.findByDocumentVersion(first.version().documentId(), 8)).isEmpty();
        assertThat(passages.findByDocumentVersion(other.version().documentId(), 7))
                .isEqualTo(expected(other));
    }

    @Test
    void concurrentPreparationsCommitOneSnapshotAndKeepTheSameIds() throws Exception {
        var prepared = fixture(false, "Note");
        var ready = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var calls =
                    List.<java.util.concurrent.Callable<Boolean>>of(
                            () -> {
                                ready.await(5, TimeUnit.SECONDS);
                                return transaction.execute(status -> passages.insert(prepared));
                            },
                            () -> {
                                ready.await(5, TimeUnit.SECONDS);
                                return transaction.execute(status -> passages.insert(prepared));
                            });
            var results = workers.invokeAll(calls, 15, TimeUnit.SECONDS);
            assertThat(
                            List.of(
                                    results.get(0).get(1, TimeUnit.SECONDS),
                                    results.get(1).get(1, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(passages.findByDocumentVersion(prepared.version().documentId(), 7))
                    .isEqualTo(expected(prepared));
        }
    }

    @Test
    void callerRollbackRemovesRowsAndConfigurationBindingAndAllowsAnIdenticalRetry()
            throws Exception {
        var prepared = fixture(false, "Note");
        transaction.executeWithoutResult(
                status -> {
                    assertThat(passages.insert(prepared)).isTrue();
                    assertThat(passages.findByDocumentVersion(prepared.version().documentId(), 7))
                            .isEqualTo(expected(prepared));
                    status.setRollbackOnly();
                });
        assertUnprepared(prepared);
        assertThat(transaction.<Boolean>execute(status -> passages.insert(prepared))).isTrue();
        assertThat(passages.findByDocumentVersion(prepared.version().documentId(), 7))
                .isEqualTo(expected(prepared));
    }

    @Test
    void failureInASecondBatchRollsBackEarlierRowsAndCanRetry() throws Exception {
        var prepared = fixture(false, "é📚".repeat(102500));
        jdbc.sql(
                        "ALTER TABLE document_passages ADD CONSTRAINT passage_test_fault CHECK (sequence_number <> 101) NOT VALID")
                .update();
        try {
            assertThatThrownBy(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> passages.insert(prepared)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertUnprepared(prepared);
        } finally {
            jdbc.sql("ALTER TABLE document_passages DROP CONSTRAINT passage_test_fault").update();
        }
        assertThat(transaction.<Boolean>execute(status -> passages.insert(prepared))).isTrue();
        assertThat(passages.findByDocumentVersion(prepared.version().documentId(), 7))
                .isEqualTo(expected(prepared));
    }

    @Test
    void requiresANewGenerationForChangedConfigurationOrPreparedContent() throws Exception {
        var prepared = fixture(false, "Note");
        transaction.executeWithoutResult(status -> passages.insert(prepared));
        var changed = new ArrayList<>(prepared.passages());
        var first = changed.getFirst();
        changed.set(0, new PreparedPassages.Passage(first.sequence(), "Changed", first.location()));
        for (var conflicting :
                List.of(
                        new PreparedPassages(prepared.version(), 2, prepared.passages()),
                        new PreparedPassages(prepared.version(), 1, changed))) {
            assertThatThrownBy(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> passages.insert(conflicting)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Stored passage snapshot cannot change within an index generation");
            assertThat(passages.findByDocumentVersion(prepared.version().documentId(), 7))
                    .isEqualTo(expected(prepared));
        }
    }

    @ParameterizedTest
    @EnumSource(Stale.class)
    void staleOrDeletedGenerationsCannotCreatePassages(Stale condition) throws Exception {
        var prepared = fixture(false, "Note");
        var id = prepared.version().documentId();
        switch (condition) {
            case SUPERSEDED ->
                    transaction.executeWithoutResult(
                            status -> indexes.request(id, OptionalLong.of(8), AT));
            case SOURCE_CHANGED ->
                    jdbc.sql("UPDATE documents SET processing_version = 3 WHERE document_id = :id")
                            .param("id", id)
                            .update();
            case DELETED ->
                    jdbc.sql("UPDATE documents SET deleted_at = updated_at WHERE document_id = :id")
                            .param("id", id)
                            .update();
            case REMOVED ->
                    jdbc.sql("DELETE FROM documents WHERE document_id = :id")
                            .param("id", id)
                            .update();
        }
        assertThatThrownBy(
                        () -> transaction.executeWithoutResult(status -> passages.insert(prepared)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Passage index generation is no longer eligible");
        assertThat(jdbc.sql("SELECT count(*) FROM document_passages").query(Long.class).single())
                .isZero();
    }

    @Test
    void databaseEnforcesPassageIdentityVersionOwnershipAndLocationBounds() throws Exception {
        var prepared = fixture(false, "Note");
        transaction.executeWithoutResult(status -> passages.insert(prepared));
        var first = expected(prepared).getFirst();
        for (var update :
                List.of(
                        "sequence_number = 0",
                        "passage_text = ''",
                        "passage_text = repeat('x', 1201)",
                        "start_line = 0",
                        "start_column = -1",
                        "end_line = 0",
                        "end_column = -1",
                        "page_number = 0")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.sql(
                                                    "UPDATE document_passages SET "
                                                            + update
                                                            + " WHERE passage_id = :id")
                                            .param("id", first.passageId())
                                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        for (var update :
                List.of(
                        "passage_configuration_version = 0",
                        "passage_count = 0",
                        "passage_configuration_version = NULL")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.sql(
                                                    "UPDATE document_index_versions SET "
                                                            + update
                                                            + " WHERE document_id = :id")
                                            .param("id", first.documentId())
                                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> insertDirect(first.passageId(), first.documentId(), 7, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDirect(UUID.randomUUID(), first.documentId(), 7, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDirect(UUID.randomUUID(), first.documentId(), 99, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDirect(UUID.randomUUID(), UUID.randomUUID(), 7, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(passages.findByDocumentVersion(first.documentId(), 7))
                .isEqualTo(expected(prepared));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void neverReturnsAPartialSnapshotAfterRowsAreLost(boolean removeLast) throws Exception {
        var prepared = fixture(false, "x".repeat(2201));
        transaction.executeWithoutResult(status -> passages.insert(prepared));
        jdbc.sql(
                        "DELETE FROM document_passages WHERE document_id = :id AND sequence_number = :sequence")
                .param("id", prepared.version().documentId())
                .param("sequence", removeLast ? prepared.passages().size() : 1)
                .update();
        assertThatThrownBy(() -> passages.findByDocumentVersion(prepared.version().documentId(), 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored passage snapshot is incomplete");
    }

    @Test
    void cannotCommitPassagesOutsideTheCallersTransaction() throws Exception {
        var prepared = fixture(false, "Note");
        assertThatThrownBy(() -> passages.insert(prepared))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertUnprepared(prepared);
    }

    private static PreparedPassages fixture(boolean paged, String content) throws Exception {
        var id = UUID.randomUUID();
        var text =
                new ExtractedText(
                        id,
                        2,
                        AT,
                        List.of(
                                new ExtractedText.Line(
                                        1,
                                        content,
                                        paged ? OptionalInt.of(1) : OptionalInt.empty()),
                                new ExtractedText.Line(
                                        2, "", paged ? OptionalInt.of(2) : OptionalInt.empty()),
                                new ExtractedText.Line(
                                        3,
                                        "# Study café",
                                        paged ? OptionalInt.of(3) : OptionalInt.empty())));
        documents.insert(
                new DocumentMetadata(
                        id,
                        "fixture.txt",
                        paged ? DocumentType.PDF : DocumentType.TXT,
                        UUID.randomUUID(),
                        "a".repeat(64),
                        4,
                        2,
                        ExtractionState.EXTRACTED,
                        AT,
                        AT));
        texts.insert(text);
        var version =
                transaction.execute(
                        status -> indexes.request(id, OptionalLong.of(7), AT).orElseThrow());
        return new PassageSplitter().split(version, text);
    }

    private static List<StoredPassage> expected(PreparedPassages prepared) {
        return prepared.passages().stream()
                .map(p -> StoredPassage.from(prepared.version(), p))
                .toList();
    }

    private static void assertUnprepared(PreparedPassages prepared) {
        var id = prepared.version().documentId();
        assertThat(passages.findByDocumentVersion(id, 7)).isEmpty();
        assertThat(
                        jdbc.sql("SELECT count(*) FROM document_passages WHERE document_id = :id")
                                .param("id", id)
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM document_index_versions WHERE document_id = :id AND passage_configuration_version IS NULL AND passage_count IS NULL")
                                .param("id", id)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    private static void insertDirect(UUID passage, UUID document, long version, int sequence) {
        jdbc.sql(
                        "INSERT INTO document_passages VALUES (:passage, :document, :version, :sequence, 'Note', 1, 0, 1, 4, NULL)")
                .param("passage", passage)
                .param("document", document)
                .param("version", version)
                .param("sequence", sequence)
                .update();
    }

    enum Stale {
        SUPERSEDED,
        SOURCE_CHANGED,
        DELETED,
        REMOVED
    }
}
