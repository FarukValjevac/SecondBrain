package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Testcontainers
class DocumentRepositoryIT {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    private static JdbcDocumentRepository repository;
    private static JdbcExtractedTextRepository extractedTextRepository;
    private static JdbcClient jdbc;
    private static PlatformTransactionManager transactionManager;
    private static final Instant EXTRACTED_AT = Instant.parse("2026-01-01T00:00:00.123456Z");

    @BeforeAll
    static void migrateDatabaseAndCreateRepository() {
        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        repository = new JdbcDocumentRepository(jdbc);
        extractedTextRepository =
                new JdbcExtractedTextRepository(new JdbcTemplate(dataSource), transactionManager);
    }

    @Test
    void insertsAndReadsMetadataWithoutLosingValues() {
        var createdAt = Instant.parse("2026-01-01T00:00:00.123456Z");
        var metadata =
                new DocumentMetadata(
                        UUID.randomUUID(),
                        "meeting notes.md",
                        DocumentType.MARKDOWN,
                        UUID.randomUUID(),
                        "a".repeat(64),
                        123,
                        1,
                        ExtractionState.QUEUED,
                        createdAt,
                        createdAt.plusSeconds(5));

        repository.insert(metadata);

        assertThat(repository.findById(metadata.documentId())).contains(metadata);
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void roundTripsBatchedUnicodeAndEmptyLinesWithDocumentAndVersionIsolation() {
        var documentId = insertDocument();
        var lines = new ArrayList<ExtractedText.Line>();
        for (var number = 1; number <= 205; number++) {
            lines.add(new ExtractedText.Line(number, number == 2 ? "" : "Café 📚 " + number));
        }
        var first = new ExtractedText(documentId, 1, EXTRACTED_AT, lines);
        var second = text(documentId, 2, "Another version");
        var otherDocument = text(insertDocument(), 1, "Another document");

        extractedTextRepository.insert(first);
        extractedTextRepository.insert(second);
        extractedTextRepository.insert(otherDocument);

        assertThat(extractedTextRepository.findByDocumentVersion(documentId, 1)).contains(first);
        assertThat(extractedTextRepository.findByDocumentVersion(documentId, 2)).contains(second);
        assertThat(extractedTextRepository.findByDocumentVersion(otherDocument.documentId(), 1))
                .contains(otherDocument);
        assertThat(extractedTextRepository.findByDocumentVersion(documentId, 3)).isEmpty();
        assertThat(extractedTextRepository.findByDocumentVersion(UUID.randomUUID(), 1)).isEmpty();
        assertThat(repository.findById(documentId).orElseThrow().extractionState())
                .isEqualTo(ExtractionState.QUEUED);
    }

    @Test
    void doesNotOverwriteAnAlreadyStoredProcessingVersion() {
        var original = text(insertDocument(), 1, "Original text");
        extractedTextRepository.insert(original);

        assertThatThrownBy(
                        () ->
                                extractedTextRepository.insert(
                                        text(original.documentId(), 1, "Replacement text")))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(extractedTextRepository.findByDocumentVersion(original.documentId(), 1))
                .contains(original);
    }

    @Test
    void rollsBackHeaderAndLinesOnBatchFailureAndCanRetry() {
        var lines = new ArrayList<ExtractedText.Line>();
        for (var number = 1; number <= 102; number++) {
            lines.add(new ExtractedText.Line(number, "Line " + number));
        }
        var text = new ExtractedText(insertDocument(), 1, EXTRACTED_AT, lines);
        // Fail in the second batch; the header and earlier batch must also roll back.
        jdbc.sql(
                        """
                ALTER TABLE document_text_lines ADD CONSTRAINT extraction_test_later_batch
                CHECK (line_number <> 102) NOT VALID
                """)
                .update();
        try {
            assertThatThrownBy(() -> extractedTextRepository.insert(text))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(extractedTextRepository.findByDocumentVersion(text.documentId(), 1))
                    .isEmpty();
            assertThat(
                            jdbc.sql(
                                            "SELECT count(*) FROM document_text_lines WHERE document_id = :id")
                                    .param("id", text.documentId())
                                    .query(Integer.class)
                                    .single())
                    .isZero();
        } finally {
            jdbc.sql("ALTER TABLE document_text_lines DROP CONSTRAINT extraction_test_later_batch")
                    .update();
        }

        extractedTextRepository.insert(text);
        assertThat(extractedTextRepository.findByDocumentVersion(text.documentId(), 1))
                .contains(text);
    }

    @Test
    void joinsTheCallersTransactionInsteadOfCommittingIndependently() {
        var text = text(insertDocument(), 1, "Caller transaction");
        var caller = new TransactionTemplate(transactionManager);

        assertThatThrownBy(
                        () ->
                                caller.executeWithoutResult(
                                        status -> {
                                            extractedTextRepository.insert(text);
                                            assertThat(
                                                            extractedTextRepository
                                                                    .findByDocumentVersion(
                                                                            text.documentId(), 1))
                                                    .contains(text);
                                            throw new IllegalStateException("Roll back the caller");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Roll back the caller");

        assertThat(extractedTextRepository.findByDocumentVersion(text.documentId(), 1)).isEmpty();
    }

    @Test
    void reportsAnIncompleteStoredExtractionInsteadOfTreatingItAsAbsent() {
        var documentId = insertDocument();
        jdbc.sql(
                        """
                INSERT INTO document_extractions (document_id, processing_version, extracted_at)
                VALUES (:id, 1, :extractedAt)
                """)
                .param("id", documentId)
                .param("extractedAt", Timestamp.from(EXTRACTED_AT))
                .update();

        assertThatThrownBy(() -> extractedTextRepository.findByDocumentVersion(documentId, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored extraction has no text lines");
    }

    private static UUID insertDocument() {
        var id = UUID.randomUUID();
        repository.insert(
                new DocumentMetadata(
                        id,
                        "fixture.md",
                        DocumentType.MARKDOWN,
                        UUID.randomUUID(),
                        "a".repeat(64),
                        123,
                        1,
                        ExtractionState.QUEUED,
                        EXTRACTED_AT.minusSeconds(1),
                        EXTRACTED_AT.minusSeconds(1)));
        return id;
    }

    private static ExtractedText text(UUID documentId, long version, String content) {
        return new ExtractedText(
                documentId, version, EXTRACTED_AT, List.of(new ExtractedText.Line(1, content)));
    }
}
