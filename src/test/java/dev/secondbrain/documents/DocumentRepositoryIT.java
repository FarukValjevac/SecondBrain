package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.OptionalInt;
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
    void listsBoundedPagesNewestFirstWithAStableTieBreakAndNoRemovedRows() {
        var createdAt = Instant.parse("2031-01-01T00:00:00Z");
        var ids = new ArrayList<UUID>();
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(
                status -> {
                    // Roll back fixtures so test ordering cannot affect other listing assertions.
                    status.setRollbackOnly();
                    for (int i = 0; i < 54; i++) {
                        var id = new UUID(0, i + 1);
                        ids.add(id);
                        repository.insert(
                                new DocumentMetadata(
                                        id,
                                        "Café 📚 " + i + ".md",
                                        DocumentType.MARKDOWN,
                                        UUID.randomUUID(),
                                        "a".repeat(64),
                                        20,
                                        1,
                                        ExtractionState.QUEUED,
                                        createdAt.plusSeconds(i / 2),
                                        createdAt));
                    }
                    jdbc.sql("DELETE FROM documents WHERE document_id = :id")
                            .param("id", ids.removeLast())
                            .update();
                    var expected = ids.reversed();

                    var first = repository.list(51, 0);
                    assertThat(first).hasSize(51);
                    assertThat(first)
                            .extracting(JdbcDocumentRepository.LibraryRow::documentId)
                            .containsExactlyElementsOf(expected.subList(0, 51));
                    assertThat(first.getFirst().name()).isEqualTo("Café 📚 52.md");
                    assertThat(first.getFirst().state()).isEqualTo(ExtractionState.QUEUED);
                    assertThat(first.getFirst().createdAt()).isEqualTo(createdAt.plusSeconds(26));
                    assertThat(repository.list(3, 50))
                            .extracting(JdbcDocumentRepository.LibraryRow::documentId)
                            .containsExactlyElementsOf(expected.subList(50, 53));
                    assertThat(repository.list(51, 10_000)).isEmpty();
                });
    }

    @Test
    void listingJoinsOnlyTheCurrentExtractionDeliveryWithoutDuplicatingDocuments() {
        var id = UUID.randomUUID();
        var now = Instant.parse("2032-01-01T00:00:00Z");
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            status.setRollbackOnly();
                            repository.insert(
                                    new DocumentMetadata(
                                            id,
                                            "Notes.txt",
                                            DocumentType.TXT,
                                            UUID.randomUUID(),
                                            "b".repeat(64),
                                            10,
                                            2,
                                            ExtractionState.QUEUED,
                                            now,
                                            now));
                            insertLibraryDelivery(
                                    id, 1, DocumentExtractionHandler.ID, "FAILED", now);
                            var eventId =
                                    insertLibraryDelivery(
                                            id, 2, DocumentExtractionHandler.ID, "PENDING", now);
                            jdbc.sql(
                                            """
                    INSERT INTO event_deliveries (delivery_id, event_id, handler_id, state,
                        eligible_at, created_at, updated_at)
                    VALUES (:id, :event, 'another.handler', 'FAILED', :now, :now, :now)
                    """)
                                    .param("id", UUID.randomUUID())
                                    .param("event", eventId)
                                    .param("now", Timestamp.from(now))
                                    .update();

                            var row = repository.list(1, 0).getFirst();
                            assertThat(row.documentId()).isEqualTo(id);
                            assertThat(row.deliveryState()).isEqualTo("PENDING");
                            assertThat(repository.list(51, 0))
                                    .filteredOn(item -> item.documentId().equals(id))
                                    .hasSize(1);
                        });
    }

    private static UUID insertLibraryDelivery(
            UUID id, long version, String handler, String state, Instant now) {
        var eventId = UUID.randomUUID();
        jdbc.sql(
                        """
                INSERT INTO event_keys (event_id, document_id, processing_version, event_type,
                    schema_version, fingerprint)
                VALUES (:event, :document, :version, 'DocumentAccepted', 1, :fingerprint)
                """)
                .param("event", eventId)
                .param("document", id)
                .param("version", version)
                .param("fingerprint", "a".repeat(64))
                .update();
        jdbc.sql(
                        """
                INSERT INTO domain_events (event_id, occurred_at, correlation_id, reference_ids, created_at)
                VALUES (:event, :now, :correlation, '{}', :now)
                """)
                .param("event", eventId)
                .param("now", Timestamp.from(now))
                .param("correlation", UUID.randomUUID())
                .update();
        jdbc.sql(
                        """
                INSERT INTO event_deliveries (delivery_id, event_id, handler_id, state,
                    eligible_at, created_at, updated_at)
                VALUES (:id, :event, :handler, :state, :now, :now, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("event", eventId)
                .param("handler", handler)
                .param("state", state)
                .param("now", Timestamp.from(now))
                .update();
        return eventId;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resolvesExactActiveSourceWhileItsDesiredReplacementIsOnlyStaging(boolean pdf) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            status.setRollbackOnly();
                            var fixture = sourceFixture(pdf);
                            var source =
                                    repository
                                            .source(fixture.documentId(), fixture.passageId())
                                            .orElseThrow();
                            assertThat(source.documentId()).isEqualTo(fixture.documentId());
                            assertThat(source.passageId()).isEqualTo(fixture.passageId());
                            assertThat(source.indexingVersion()).isEqualTo(7);
                            assertThat(source.name())
                                    .isEqualTo(
                                            pdf
                                                    ? "Café <script>source</script>.pdf"
                                                    : "Café <script>source</script>.md");
                            assertThat(source.text()).isEqualTo("Source café 📚\nsecond line");
                            assertThat(source.location())
                                    .isEqualTo(
                                            new DocumentInspectionService.SourceLocation(
                                                    3,
                                                    2,
                                                    4,
                                                    6,
                                                    pdf ? OptionalInt.of(7) : OptionalInt.empty()));
                        });
    }

    @ParameterizedTest
    @EnumSource(SourceExclusion.class)
    void excludesUnavailableAndWronglyOwnedPassageSources(SourceExclusion exclusion) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            status.setRollbackOnly();
                            var fixture = sourceFixture(false);
                            String change =
                                    switch (exclusion) {
                                        case DELETED ->
                                                "UPDATE documents SET deleted_at = now() WHERE document_id = :id";
                                        case REMOVED ->
                                                "DELETE FROM documents WHERE document_id = :id";
                                        case QUEUED ->
                                                "UPDATE documents SET extraction_state = 'QUEUED' WHERE document_id = :id";
                                        case PROCESSING ->
                                                "UPDATE documents SET extraction_state = 'PROCESSING' WHERE document_id = :id";
                                        case FAILED ->
                                                "UPDATE documents SET extraction_state = 'FAILED' WHERE document_id = :id";
                                        case SOURCE_CHANGED ->
                                                "UPDATE documents SET processing_version = 2 WHERE document_id = :id";
                                        case STAGED ->
                                                "UPDATE documents SET active_indexing_version = NULL WHERE document_id = :id";
                                        case REPLACED ->
                                                "UPDATE documents SET active_indexing_version = 8 WHERE document_id = :id";
                                        case MISSING_VECTOR ->
                                                "DELETE FROM document_embeddings WHERE document_id = :id AND passage_id <> :passage";
                                        case MISSING_PASSAGE ->
                                                "DELETE FROM document_passages WHERE document_id = :id AND passage_id <> :passage";
                                        case GAP ->
                                                "UPDATE document_passages SET sequence_number = 3 WHERE document_id = :id AND sequence_number = 2";
                                        case CONFIGURATION ->
                                                "UPDATE document_index_versions SET passage_configuration_version = 2 WHERE document_id = :id AND indexing_version = 7";
                                        case UNBOUND ->
                                                "DELETE FROM document_embeddings WHERE document_id = :id";
                                        case WRONG_DOCUMENT, UNKNOWN_PASSAGE -> "";
                                    };
                            if (!change.isEmpty()) {
                                var statement = jdbc.sql(change).param("id", fixture.documentId());
                                if (change.contains(":passage"))
                                    statement.param("passage", fixture.passageId());
                                statement.update();
                            }
                            if (exclusion == SourceExclusion.UNBOUND)
                                jdbc.sql(
                                                "UPDATE document_index_versions SET embedding_model_id = NULL, embedding_dimensions = NULL WHERE document_id = :id AND indexing_version = 7")
                                        .param("id", fixture.documentId())
                                        .update();
                            assertThat(
                                            repository.source(
                                                    exclusion == SourceExclusion.WRONG_DOCUMENT
                                                            ? sourceFixture(false).documentId()
                                                            : fixture.documentId(),
                                                    exclusion == SourceExclusion.UNKNOWN_PASSAGE
                                                            ? UUID.randomUUID()
                                                            : fixture.passageId()))
                                    .isEmpty();
                        });
    }

    @Test
    void publicSourceReadCannotReuseACallersPreDeletionSnapshot() {
        var fixture = sourceFixture(false); // Committed before the repeatable-read caller begins.
        var service =
                new DocumentInspectionService(
                        repository,
                        extractedTextRepository,
                        org.mockito.Mockito.mock(dev.secondbrain.events.EventPublisher.class),
                        transactionManager,
                        java.time.Clock.systemUTC());
        var outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.setTimeout(5);
        try {
            outer.executeWithoutResult(
                    status -> {
                        status.setRollbackOnly();
                        assertThat(repository.source(fixture.documentId(), fixture.passageId()))
                                .isPresent();
                        var independent =
                                new JdbcDocumentRepository(
                                        JdbcClient.create(
                                                new DriverManagerDataSource(
                                                        POSTGRES.getJdbcUrl(),
                                                        POSTGRES.getUsername(),
                                                        POSTGRES.getPassword())));
                        assertThat(independent.requestDeletion(fixture.documentId(), EXTRACTED_AT))
                                .isPresent();
                        assertThat(service.source(fixture.documentId(), fixture.passageId()))
                                .isEmpty();
                        assertThat(repository.source(fixture.documentId(), fixture.passageId()))
                                .isPresent();
                    });
        } finally {
            jdbc.sql("DELETE FROM documents WHERE document_id = :id")
                    .param("id", fixture.documentId())
                    .update();
        }
    }

    @Test
    void batchNamesReturnOnlyRequestedVisibleDocuments() {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            status.setRollbackOnly();
                            var visible = insertDocument();
                            var hidden = insertDocument();
                            var removed = insertDocument();
                            insertDocument(); // Visible, but outside the requested IDs.
                            jdbc.sql(
                                            "UPDATE documents SET original_name = :name WHERE document_id = :id")
                                    .param("id", visible)
                                    .param("name", "Café 📚 <script>.md")
                                    .update();
                            repository.requestDeletion(hidden, EXTRACTED_AT);
                            jdbc.sql("DELETE FROM documents WHERE document_id = :id")
                                    .param("id", removed)
                                    .update();
                            var names =
                                    repository.names(
                                            java.util.Set.of(
                                                    visible, hidden, removed, UUID.randomUUID()));
                            assertThat(names)
                                    .containsExactlyEntriesOf(
                                            java.util.Map.of(visible, "Café 📚 <script>.md"));
                            assertThat(repository.names(java.util.Set.of())).isEmpty();
                            assertThatThrownBy(() -> names.clear())
                                    .isInstanceOf(UnsupportedOperationException.class);
                        });
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void roundTripsBatchedUnicodeAndEmptyLinesWithDocumentAndVersionIsolation(boolean paged) {
        var documentId = insertDocument();
        var lines = new ArrayList<ExtractedText.Line>();
        for (var number = 1; number <= 205; number++) {
            // Page two is blank; lines 4 onward share page three, across JDBC batches.
            var page =
                    paged
                            ? OptionalInt.of(number <= 2 ? 1 : number == 3 ? 2 : 3)
                            : OptionalInt.empty();
            var content = number == 2 || number == 3 ? "" : "Café 📚 " + number;
            lines.add(new ExtractedText.Line(number, content, page));
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

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void databaseRejectsNonPositivePageNumbers(int pageNumber) {
        var original =
                new ExtractedText(
                        insertDocument(),
                        1,
                        EXTRACTED_AT,
                        List.of(new ExtractedText.Line(1, "Paged text", OptionalInt.of(2))));
        extractedTextRepository.insert(original);

        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                """
                UPDATE document_text_lines SET page_number = :page
                WHERE document_id = :id AND processing_version = 1 AND line_number = 1
                """)
                                        .param("page", pageNumber)
                                        .param("id", original.documentId())
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(extractedTextRepository.findByDocumentVersion(original.documentId(), 1))
                .contains(original);
    }

    @Test
    void persistsEverySupportedFailureCodeIncludingPdfReasons() {
        for (var reason : TextExtractionException.Reason.values()) {
            var id = insertDocument();
            assertThat(repository.markFailed(id, 1, reason, EXTRACTED_AT)).isTrue();
            assertThat(repository.progress(id, 1).failure()).contains(reason);
        }
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

    private static SourceFixture sourceFixture(boolean pdf) {
        var document = UUID.randomUUID();
        repository.insert(
                new DocumentMetadata(
                        document,
                        pdf
                                ? "Café <script>source</script>.pdf"
                                : "Café <script>source</script>.md",
                        pdf ? DocumentType.PDF : DocumentType.MARKDOWN,
                        UUID.randomUUID(),
                        "c".repeat(64),
                        64,
                        1,
                        ExtractionState.EXTRACTED,
                        EXTRACTED_AT,
                        EXTRACTED_AT));
        jdbc.sql(
                        "INSERT INTO document_extractions(document_id, processing_version, extracted_at) VALUES (:id, 1, :at)")
                .param("id", document)
                .param("at", java.sql.Timestamp.from(EXTRACTED_AT))
                .update();
        jdbc.sql(
                        """
                INSERT INTO document_index_versions(document_id, indexing_version, extraction_version, created_at,
                    passage_configuration_version, passage_count, embedding_model_id, embedding_dimensions)
                VALUES (:id, 7, 1, :at, 1, 2, :model, 1024), (:id, 8, 1, :at, NULL, NULL, NULL, NULL)
                """)
                .param("id", document)
                .param("at", java.sql.Timestamp.from(EXTRACTED_AT))
                .param("model", dev.secondbrain.embeddings.EmbeddingModel.BGE_M3_ID)
                .update();
        UUID first = null;
        for (int sequence = 1; sequence <= 2; sequence++) {
            var passage =
                    UUID.nameUUIDFromBytes(
                            ("secondbrain:passage:v1:" + document + ":7:" + sequence)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (sequence == 1) first = passage;
            jdbc.sql(
                            """
                    INSERT INTO document_passages(passage_id, document_id, indexing_version, sequence_number,
                        passage_text, start_line, start_column, end_line, end_column, page_number)
                    VALUES (:passage, :document, 7, :sequence, :text, 3, 2, 4, 6, :page)
                    """)
                    .param("passage", passage)
                    .param("document", document)
                    .param("sequence", sequence)
                    .param("text", "Source café 📚\nsecond line")
                    .param("page", pdf ? 7 : null)
                    .update();
            jdbc.sql(
                            """
                    INSERT INTO document_embeddings(passage_id, document_id, indexing_version, model_id, dimensions, embedding)
                    VALUES (:passage, :document, 7, :model, 1024, CAST(:vector AS vector(1024)))
                    """)
                    .param("passage", passage)
                    .param("document", document)
                    .param("model", dev.secondbrain.embeddings.EmbeddingModel.BGE_M3_ID)
                    .param("vector", "[1" + ",0".repeat(1023) + "]")
                    .update();
        }
        jdbc.sql(
                        "UPDATE documents SET active_indexing_version = 7, desired_indexing_version = 8 WHERE document_id = :id")
                .param("id", document)
                .update();
        return new SourceFixture(document, java.util.Objects.requireNonNull(first));
    }

    private record SourceFixture(UUID documentId, UUID passageId) {}

    private enum SourceExclusion {
        DELETED,
        REMOVED,
        QUEUED,
        PROCESSING,
        FAILED,
        SOURCE_CHANGED,
        STAGED,
        REPLACED,
        MISSING_VECTOR,
        MISSING_PASSAGE,
        GAP,
        CONFIGURATION,
        UNBOUND,
        WRONG_DOCUMENT,
        UNKNOWN_PASSAGE
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
