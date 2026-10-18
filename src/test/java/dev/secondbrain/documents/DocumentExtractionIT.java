package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventHandler;
import dev.secondbrain.events.EventPublisher;

import io.micrometer.core.instrument.MeterRegistry;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Testcontainers
class DocumentExtractionIT {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir Path storage;

    @AfterEach
    void resetOnlyTheTestDatabase() {
        JdbcClient.create(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()))
                .sql(
                        "TRUNCATE TABLE documents, event_keys, domain_events, event_deliveries, event_delivery_attempts CASCADE")
                .update();
    }

    @Test
    void schedulesLegacyAcceptanceOnceAndCompletesAfterRestart() throws Exception {
        var content = "# Étude 📚\r\n\r\nStudy\n".getBytes(StandardCharsets.UTF_8);
        UUID id;
        UUID storageId;
        try (var context = start(false, false, false)) {
            id = upload(context, content);
            storageId =
                    context.getBean(JdbcDocumentRepository.class)
                            .findById(id)
                            .orElseThrow()
                            .storageId();
            assertThat(deliveryCount(context, id)).isZero();
        }
        UUID acceptedId;
        UUID extractedId;
        try (var context = start(true, true, true)) {
            awaitState(context, id, "COMPLETED");
            var view = context.getBean(DocumentInspectionService.class).find(id).orElseThrow();
            assertThat(view.state()).isEqualTo(ExtractionState.EXTRACTED);
            assertThat(view.lines())
                    .containsExactly(
                            new DocumentInspectionService.Line(1, "# Étude 📚"),
                            new DocumentInspectionService.Line(2, ""),
                            new DocumentInspectionService.Line(3, "Study"),
                            new DocumentInspectionService.Line(4, ""));
            assertThat(deliveryCount(context, id)).isEqualTo(1);
            assertThat(
                            context.getBean(EventPublisher.class)
                                    .scheduleMissing(
                                            DocumentAcceptedEvent.TYPE,
                                            DocumentExtractionHandler.ID,
                                            100))
                    .isZero();
            acceptedId = eventId(context, id, "DocumentAccepted");
            extractedId = eventId(context, id, "TextExtracted");
            var causation =
                    jdbc(context)
                            .sql("SELECT causation_id FROM domain_events WHERE event_id = :id")
                            .param("id", extractedId)
                            .query(UUID.class)
                            .single();
            assertThat(causation).isEqualTo(acceptedId);
            assertThat(
                            context.getBean(MeterRegistry.class)
                                    .get("secondbrain.event.delivery.attempts")
                                    .tag("outcome", "completed")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
        }
        try (var context = start(true, true, true)) {
            assertThat(
                            context.getBean(DocumentInspectionService.class)
                                    .find(id)
                                    .orElseThrow()
                                    .state())
                    .isEqualTo(ExtractionState.EXTRACTED);
            assertThat(eventId(context, id, "DocumentAccepted")).isEqualTo(acceptedId);
            assertThat(eventId(context, id, "TextExtracted")).isEqualTo(extractedId);
            assertThat(deliveryCount(context, id)).isEqualTo(1);
        }
        assertThat(Files.readAllBytes(storage.resolve(storageId + ".original")))
                .containsExactly(content);
    }

    @Test
    void recordsEncodingFailureAndRetriesANewVersionWithoutChangingDocumentIdentity()
            throws Exception {
        try (var context = start(true, true, true)) {
            var id = upload(context, new byte[] {(byte) 0xC3, 0x28});
            awaitState(context, id, "FAILED");
            var inspection = context.getBean(DocumentInspectionService.class);
            var failed = inspection.find(id).orElseThrow();
            assertThat(failed.state()).isEqualTo(ExtractionState.FAILED);
            assertThat(failed.message()).contains("UTF-8");
            assertThat(failed.retryAllowed()).isTrue();
            assertThat(failed.lines()).isEmpty();
            assertNoText(context, id);
            var metadata = context.getBean(JdbcDocumentRepository.class).findById(id).orElseThrow();
            assertThat(inspection.retry(id, UUID.randomUUID())).isTrue();
            awaitState(context, id, "FAILED");
            var retried = inspection.find(id).orElseThrow();
            assertThat(retried.documentId()).isEqualTo(id);
            assertThat(retried.processingVersion()).isEqualTo(2);
            assertThat(
                            context.getBean(JdbcDocumentRepository.class)
                                    .findById(id)
                                    .orElseThrow()
                                    .storageId())
                    .isEqualTo(metadata.storageId());
            assertNoText(context, id);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = DocumentType.class,
            names = {"TXT", "PDF"})
    void boundsMissingOriginalRetriesAndShowsATerminalStorageFailure(DocumentType type)
            throws Exception {
        UUID id;
        var content =
                type == DocumentType.PDF
                        ? PdfFixtures.text("Note")
                        : "Note".getBytes(StandardCharsets.UTF_8);
        try (var context = start(true, false, false)) {
            id =
                    upload(
                            context,
                            type == DocumentType.PDF ? "study.pdf" : "study.txt",
                            content,
                            UUID.randomUUID());
            var metadata = context.getBean(JdbcDocumentRepository.class).findById(id).orElseThrow();
            Files.delete(storage.resolve(metadata.storageId() + ".original"));
        }
        try (var context = start(true, true, true)) {
            awaitState(context, id, "FAILED");
            assertThat(
                            jdbc(context)
                                    .sql(
                                            """
                    SELECT d.attempts FROM event_deliveries d JOIN event_keys k USING(event_id)
                    WHERE k.document_id = :id AND k.event_type = 'DocumentAccepted'
                    """)
                                    .param("id", id)
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(2);
            var view = context.getBean(DocumentInspectionService.class).find(id).orElseThrow();
            assertThat(view.message())
                    .contains("could not be read")
                    .doesNotContain(storage.toString());
            assertThat(view.retryAllowed()).isTrue();
            assertNoText(context, id);
            var metadata = context.getBean(JdbcDocumentRepository.class).findById(id).orElseThrow();
            Files.write(storage.resolve(metadata.storageId() + ".original"), content);
            assertThat(
                            context.getBean(DocumentInspectionService.class)
                                    .retry(id, UUID.randomUUID()))
                    .isTrue();
            awaitState(context, id, "COMPLETED");
            var recovered = context.getBean(DocumentInspectionService.class).find(id).orElseThrow();
            assertThat(recovered.processingVersion()).isEqualTo(2);
            assertThat(recovered.state()).isEqualTo(ExtractionState.EXTRACTED);
            assertThat(recovered.lines())
                    .filteredOn(line -> !line.text().isEmpty())
                    .containsExactly(
                            new DocumentInspectionService.Line(
                                    1,
                                    "Note",
                                    type == DocumentType.PDF
                                            ? OptionalInt.of(1)
                                            : OptionalInt.empty()));
            if (type == DocumentType.PDF) {
                assertThat(
                                context.getBean(JdbcExtractedTextRepository.class)
                                        .findByDocumentVersion(id, 2)
                                        .orElseThrow()
                                        .lines())
                        .allSatisfy(line -> assertThat(line.pageNumber()).hasValue(1));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aPreparedOldWorkerCannotPublishAfterVersionChangeOrPhysicalDeletion(boolean delete)
            throws Exception {
        try (var context = start(true, true, true)) {
            var gate = context.getBean(Gate.class);
            gate.block = true;
            var id = upload(context, "Prepared note".getBytes(StandardCharsets.UTF_8));
            try {
                assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
                jdbc(context)
                        .sql(
                                delete
                                        ? "DELETE FROM documents WHERE document_id = :id"
                                        : "UPDATE documents SET processing_version = 2 WHERE document_id = :id")
                        .param("id", id)
                        .update();
            } finally {
                gate.release.countDown();
            }
            // This checks the old version's delivery, even after the current version changes.
            Awaitility.await()
                    .atMost(Duration.ofSeconds(15))
                    .untilAsserted(
                            () ->
                                    assertThat(
                                                    jdbc(context)
                                                            .sql(
                                                                    """
                            SELECT d.state FROM event_deliveries d JOIN event_keys k USING(event_id)
                            WHERE k.document_id = :id AND k.processing_version = 1
                            """)
                                                            .param("id", id)
                                                            .query(String.class)
                                                            .single())
                                            .isEqualTo("SKIPPED"));
            assertNoText(context, id);
        }
    }

    @Test
    void reconcilesInBoundedIdempotentBatches() throws Exception {
        UUID first;
        UUID second;
        try (var context = start(false, false, false)) {
            first = upload(context, "One".getBytes(StandardCharsets.UTF_8));
            second = upload(context, "Two".getBytes(StandardCharsets.UTF_8));
        }
        try (var context = start(true, false, false)) {
            var publisher = context.getBean(EventPublisher.class);
            assertThat(
                            publisher.scheduleMissing(
                                    DocumentAcceptedEvent.TYPE, DocumentExtractionHandler.ID, 1))
                    .isEqualTo(1);
            assertThat(
                            publisher.scheduleMissing(
                                    DocumentAcceptedEvent.TYPE, DocumentExtractionHandler.ID, 1))
                    .isEqualTo(1);
            assertThat(
                            publisher.scheduleMissing(
                                    DocumentAcceptedEvent.TYPE, DocumentExtractionHandler.ID, 1))
                    .isZero();
            assertThat(deliveryCount(context, first)).isEqualTo(1);
            assertThat(deliveryCount(context, second)).isEqualTo(1);
        }
    }

    @Test
    void extractsPdfPagesOnceAcrossRestartAndDeletesTheirContent() throws Exception {
        var bytes = PdfFixtures.text("Café study notes", "", "Final page");
        var correlation = UUID.randomUUID();
        UUID id;
        UUID original;
        UUID extractedEvent;
        List<ExtractedText.Line> savedLines;
        try (var context = start(true, false, false)) {
            id = upload(context, "study.PDF", bytes, correlation);
            var metadata = context.getBean(JdbcDocumentRepository.class).findById(id).orElseThrow();
            original = metadata.storageId();
            assertThat(metadata.documentType()).isEqualTo(DocumentType.PDF);
            assertThat(metadata.extractionState()).isEqualTo(ExtractionState.QUEUED);
            assertNoText(context, id);
        }
        try (var context = start(true, true, true)) {
            awaitState(context, id, "COMPLETED");
            savedLines =
                    context.getBean(JdbcExtractedTextRepository.class)
                            .findByDocumentVersion(id, 1)
                            .orElseThrow()
                            .lines();
            assertThat(savedLines)
                    .filteredOn(line -> !line.text().isBlank())
                    .extracting(line -> line.pageNumber().orElseThrow())
                    .containsExactly(1, 3);
            assertThat(savedLines)
                    .anySatisfy(
                            line -> {
                                assertThat(line.pageNumber()).hasValue(2);
                                assertThat(line.text()).isEmpty();
                            });
            extractedEvent = eventId(context, id, "TextExtracted");
            var acceptedEvent = eventId(context, id, "DocumentAccepted");
            assertThat(
                            jdbc(context)
                                    .sql(
                                            "SELECT correlation_id FROM domain_events WHERE event_id = :id")
                                    .param("id", extractedEvent)
                                    .query(UUID.class)
                                    .single())
                    .isEqualTo(correlation);
            assertThat(
                            jdbc(context)
                                    .sql(
                                            "SELECT causation_id FROM domain_events WHERE event_id = :id")
                                    .param("id", extractedEvent)
                                    .query(UUID.class)
                                    .single())
                    .isEqualTo(acceptedEvent);
            var duplicate =
                    new EventEnvelope(
                            acceptedEvent,
                            DocumentAcceptedEvent.TYPE,
                            id,
                            1,
                            Instant.EPOCH,
                            correlation,
                            null,
                            null,
                            Map.of());
            assertThat(context.getBean(DocumentExtractionHandler.class).prepare(duplicate).apply())
                    .isEqualTo(EventHandler.Outcome.SKIPPED);
            assertThat(eventId(context, id, "TextExtracted")).isEqualTo(extractedEvent);
            assertThat(
                            context.getBean(EventPublisher.class)
                                    .scheduleMissing(
                                            DocumentAcceptedEvent.TYPE,
                                            DocumentExtractionHandler.ID,
                                            100))
                    .isZero();
        }
        try (var context = start(true, true, true)) {
            assertThat(
                            context.getBean(JdbcExtractedTextRepository.class)
                                    .findByDocumentVersion(id, 1)
                                    .orElseThrow()
                                    .lines())
                    .isEqualTo(savedLines);
            assertThat(eventId(context, id, "TextExtracted")).isEqualTo(extractedEvent);
            assertThat(Files.readAllBytes(storage.resolve(original + ".original")))
                    .containsExactly(bytes);
            assertThat(
                            context.getBean(DocumentDeletionService.class)
                                    .request(id, UUID.randomUUID()))
                    .isTrue();
            assertThat(context.getBean(DocumentInspectionService.class).find(id)).isEmpty();
            Awaitility.await()
                    .atMost(Duration.ofSeconds(15))
                    .untilAsserted(
                            () -> {
                                assertThat(
                                                jdbc(context)
                                                        .sql(
                                                                "SELECT count(*) FROM documents WHERE document_id = :id")
                                                        .param("id", id)
                                                        .query(Integer.class)
                                                        .single())
                                        .isZero();
                                assertThat(
                                                context.getBean(JdbcExtractedTextRepository.class)
                                                        .findByDocumentVersion(id, 1))
                                        .isEmpty();
                                assertThat(Files.exists(storage.resolve(original + ".original")))
                                        .isFalse();
                            });
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = TextExtractionException.Reason.class,
            names = {"PDF_UNREADABLE", "PDF_ENCRYPTED", "PDF_NO_TEXT"})
    void pdfFailuresStopAfterOneAttemptAndRetryAsANewVersion(TextExtractionException.Reason reason)
            throws Exception {
        var bytes =
                switch (reason) {
                    case PDF_UNREADABLE ->
                            "%PDF-1.7\nPrivate truncated data".getBytes(StandardCharsets.UTF_8);
                    case PDF_ENCRYPTED -> PdfFixtures.encrypted();
                    case PDF_NO_TEXT -> PdfFixtures.text("");
                    default -> throw new IllegalArgumentException("Unsupported test fixture");
                };
        try (var context = start(true, true, true)) {
            var id = upload(context, "study.pdf", bytes, UUID.randomUUID());
            var inspection = context.getBean(DocumentInspectionService.class);
            for (var version = 1; version <= 2; version++) {
                awaitState(context, id, "FAILED");
                var view = inspection.find(id).orElseThrow();
                assertThat(view.processingVersion()).isEqualTo(version);
                assertThat(view.message()).isEqualTo(reason.message());
                assertThat(view.retryAllowed()).isTrue();
                assertThat(view.lines()).isEmpty();
                assertThat(
                                jdbc(context)
                                        .sql(
                                                """
                        SELECT delivery.attempts FROM event_deliveries delivery
                        JOIN event_keys k USING (event_id)
                        WHERE k.document_id = :id AND k.processing_version = :version
                            AND k.event_type = 'DocumentAccepted'
                        """)
                                        .param("id", id)
                                        .param("version", version)
                                        .query(Integer.class)
                                        .single())
                        .isEqualTo(1);
                assertNoText(context, id);
                if (version == 1) assertThat(inspection.retry(id, UUID.randomUUID())).isTrue();
            }
            var metadata = context.getBean(JdbcDocumentRepository.class).findById(id).orElseThrow();
            assertThat(Files.readAllBytes(storage.resolve(metadata.storageId() + ".original")))
                    .containsExactly(bytes);
        }
    }

    private static UUID upload(ConfigurableApplicationContext context, byte[] bytes)
            throws Exception {
        return upload(context, "study.md", bytes, UUID.randomUUID());
    }

    private static UUID upload(
            ConfigurableApplicationContext context, String filename, byte[] bytes, UUID correlation)
            throws Exception {
        try (var input = new ByteArrayInputStream(bytes)) {
            return context.getBean(DocumentAcceptanceService.class)
                    .accept(filename, bytes.length, input, correlation)
                    .documentId();
        }
    }

    private static void awaitState(ConfigurableApplicationContext context, UUID id, String state) {
        Awaitility.await()
                .pollInterval(Duration.ofMillis(50))
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            var current =
                                    jdbc(context)
                                            .sql(
                                                    """
                    SELECT delivery.state FROM documents d
                    JOIN event_keys k ON k.document_id = d.document_id AND k.processing_version = d.processing_version
                        AND k.event_type = 'DocumentAccepted'
                    JOIN event_deliveries delivery USING (event_id)
                    WHERE d.document_id = :id AND delivery.handler_id = :handler
                    """)
                                            .param("id", id)
                                            .param("handler", DocumentExtractionHandler.ID)
                                            .query(String.class)
                                            .optional();
                            assertThat(current).contains(state);
                        });
    }

    private static int deliveryCount(ConfigurableApplicationContext context, UUID id) {
        return jdbc(context)
                .sql(
                        """
                SELECT count(*) FROM event_deliveries d JOIN event_keys k USING (event_id) WHERE k.document_id = :id
                """)
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private static UUID eventId(ConfigurableApplicationContext context, UUID id, String type) {
        return jdbc(context)
                .sql(
                        "SELECT event_id FROM event_keys WHERE document_id = :id AND event_type = :type")
                .param("id", id)
                .param("type", type)
                .query(UUID.class)
                .single();
    }

    private static void assertNoText(ConfigurableApplicationContext context, UUID id) {
        assertThat(
                        jdbc(context)
                                .sql(
                                        "SELECT count(*) FROM document_extractions WHERE document_id = :id")
                                .param("id", id)
                                .query(Integer.class)
                                .single())
                .isZero();
        assertThat(
                        jdbc(context)
                                .sql(
                                        "SELECT count(*) FROM event_keys WHERE document_id = :id AND event_type = 'TextExtracted'")
                                .param("id", id)
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    private static JdbcClient jdbc(ConfigurableApplicationContext context) {
        return context.getBean(JdbcClient.class);
    }

    private ConfigurableApplicationContext start(
            boolean enabled, boolean polling, boolean reconcile) {
        return new SpringApplicationBuilder(SecondBrainApplication.class, GateConfiguration.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.config.import=",
                        "--secondbrain.documents.cleanup.enabled=false",
                        "--secondbrain.documents.indexing.enabled=false",
                        "--secondbrain.documents.extraction.enabled=" + enabled,
                        "--secondbrain.documents.extraction.reconcile-enabled=" + reconcile,
                        "--secondbrain.events.polling-enabled=" + polling,
                        "--secondbrain.events.poll-interval=50ms",
                        "--secondbrain.events.max-attempts=2",
                        "--secondbrain.events.initial-backoff=100ms",
                        "--secondbrain.events.max-backoff=100ms",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--secondbrain.documents.storage-directory=" + storage);
    }

    static final class Gate {
        volatile boolean block;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GateConfiguration {
        @Bean
        Gate gate() {
            return new Gate();
        }

        @Bean
        @Primary
        DocumentTextExtractionService gatedExtraction(
                OriginalFileStore originals, DocumentUploadPolicy policy, Clock clock, Gate gate) {
            var service =
                    spy(
                            new DocumentTextExtractionService(
                                    originals,
                                    new Utf8TextExtractor(policy.maximumUploadBytes()),
                                    new PdfTextExtractor(policy.maximumUploadBytes(), clock),
                                    clock));
            doAnswer(
                            invocation -> {
                                assertThat(
                                                TransactionSynchronizationManager
                                                        .isActualTransactionActive())
                                        .isFalse();
                                var result = invocation.callRealMethod();
                                if (gate.block) {
                                    gate.entered.countDown();
                                    if (!gate.release.await(10, TimeUnit.SECONDS))
                                        throw new IllegalStateException("Test gate timeout");
                                }
                                return result;
                            })
                    .when(service)
                    .extract(any());
            return service;
        }
    }
}
