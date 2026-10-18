package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.UUID;

@Testcontainers
class IndexingVersionIT {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00.123456Z");
    private static JdbcClient jdbc;
    private static JdbcDocumentRepository documents;
    private static JdbcIndexingRepository indexes;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrateIsolatedDatabase() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = JdbcClient.create(source);
        documents = new JdbcDocumentRepository(jdbc);
        indexes = new JdbcIndexingRepository(jdbc);
        transactions = new TransactionTemplate(new JdbcTransactionManager(source));
        transactions.setTimeout(5);
    }

    @AfterEach
    void clearOnlyContainerFixtures() {
        jdbc.sql("TRUNCATE TABLE documents CASCADE").update();
    }

    @Test
    void selectsOnlyTheDesiredIndexWhileKeepingItsExtractionVersionSeparate() {
        var id = extractedDocument();
        assertThat(indexes.findEligibleDesired(id)).isEmpty();
        insertVersion(id, 6, 1);
        insertVersion(id, 7, 1);
        selectVersion(id, 7);

        assertThat(indexes.findEligibleDesired(id))
                .contains(new IndexingVersion(id, 7, 1, CREATED_AT));
        assertThat(documents.findById(id).orElseThrow().processingVersion()).isEqualTo(1);
        assertThat(indexes.findEligibleDesired(UUID.randomUUID())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Ineligible.class)
    void rejectsHiddenIncompleteUnselectedOrSupersededSourceVersions(Ineligible condition) {
        var id = extractedDocument();
        insertVersion(id, 1, 1);
        selectVersion(id, 1);
        var assignment =
                switch (condition) {
                    case DELETED -> "deleted_at = updated_at";
                    case UNEXTRACTED -> "extraction_state = 'QUEUED'";
                    case SOURCE_CHANGED -> "processing_version = 2";
                    case UNSELECTED -> "desired_indexing_version = NULL";
                };
        // Closed, test-owned SQL fragments only; document values stay bound parameters.
        jdbc.sql("UPDATE documents SET " + assignment + " WHERE document_id = :id")
                .param("id", id)
                .update();

        assertThat(indexes.findEligibleDesired(id)).isEmpty();
    }

    @Test
    void enforcesPositiveUniqueVersionsAndAnExistingExtractionOfTheSameDocument() {
        var id = extractedDocument();
        var other = extractedDocument();
        jdbc.sql(
                        """
                INSERT INTO document_extractions (document_id, processing_version, extracted_at)
                VALUES (:id, 2, :at)
                """)
                .param("id", other)
                .param("at", Timestamp.from(CREATED_AT))
                .update();
        for (var pair : new long[][] {{0, 1}, {-1, 1}, {1, 0}, {1, -1}, {1, 2}}) {
            assertThatThrownBy(() -> insertVersion(id, pair[0], pair[1]))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        insertVersion(id, 1, 1);
        assertThatThrownBy(() -> insertVersion(id, 1, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertVersion(UUID.randomUUID(), 1, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void desiredVersionMustBelongToTheSameDocumentAndCannotBeRemovedWhileSelected() {
        var id = extractedDocument();
        var other = extractedDocument();
        insertVersion(id, 1, 1);
        insertVersion(other, 2, 1);
        selectVersion(id, 1);

        assertThatThrownBy(() -> selectVersion(id, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> selectVersion(id, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "DELETE FROM document_index_versions WHERE document_id = :id")
                                        .param("id", id)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(indexes.findEligibleDesired(id))
                .contains(new IndexingVersion(id, 1, 1, CREATED_AT));
    }

    @Test
    void versionSelectionCannotCommitWithoutItsMetadataAndRollsBackWithTheCaller() {
        var id = extractedDocument();
        transactions.executeWithoutResult(
                status -> {
                    // The deferred FK permits either statement order inside one atomic operation.
                    selectVersion(id, 3);
                    insertVersion(id, 3, 1);
                });
        assertThat(indexes.findEligibleDesired(id))
                .contains(new IndexingVersion(id, 3, 1, CREATED_AT));

        transactions.executeWithoutResult(
                status -> {
                    selectVersion(id, 4);
                    insertVersion(id, 4, 1);
                    assertThat(indexes.findEligibleDesired(id))
                            .contains(new IndexingVersion(id, 4, 1, CREATED_AT));
                    status.setRollbackOnly();
                });
        assertThat(indexes.findEligibleDesired(id))
                .contains(new IndexingVersion(id, 3, 1, CREATED_AT));
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM document_index_versions WHERE document_id = :id")
                                .param("id", id)
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> selectVersion(id, 5)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(indexes.findEligibleDesired(id))
                .contains(new IndexingVersion(id, 3, 1, CREATED_AT));
    }

    @Test
    void deletingADocumentCascadesAllIndexVersionsDespiteItsDesiredPointer() {
        var id = extractedDocument();
        insertVersion(id, 1, 1);
        insertVersion(id, 2, 1);
        selectVersion(id, 2);

        jdbc.sql("DELETE FROM documents WHERE document_id = :id").param("id", id).update();

        assertThat(indexes.findEligibleDesired(id)).isEmpty();
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM document_index_versions WHERE document_id = :id")
                                .param("id", id)
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    private static UUID extractedDocument() {
        var id = UUID.randomUUID();
        documents.insert(
                new DocumentMetadata(
                        id,
                        "fixture.txt",
                        DocumentType.TXT,
                        UUID.randomUUID(),
                        "a".repeat(64),
                        4,
                        1,
                        ExtractionState.EXTRACTED,
                        CREATED_AT,
                        CREATED_AT));
        jdbc.sql(
                        """
                INSERT INTO document_extractions(document_id, processing_version, extracted_at)
                VALUES (:id, 1, :at)
                """)
                .param("id", id)
                .param("at", Timestamp.from(CREATED_AT))
                .update();
        jdbc.sql(
                        """
                INSERT INTO document_text_lines(document_id, processing_version, line_number, line_text)
                VALUES (:id, 1, 1, 'Note')
                """)
                .param("id", id)
                .update();
        return id;
    }

    private static void insertVersion(UUID id, long version, long extraction) {
        jdbc.sql(
                        """
                INSERT INTO document_index_versions(document_id, indexing_version, extraction_version, created_at)
                VALUES (:id, :version, :extraction, :at)
                """)
                .param("id", id)
                .param("version", version)
                .param("extraction", extraction)
                .param("at", Timestamp.from(CREATED_AT))
                .update();
    }

    private static void selectVersion(UUID id, long version) {
        jdbc.sql("UPDATE documents SET desired_indexing_version = :version WHERE document_id = :id")
                .param("id", id)
                .param("version", version)
                .update();
    }

    enum Ineligible {
        DELETED,
        UNEXTRACTED,
        SOURCE_CHANGED,
        UNSELECTED
    }
}
