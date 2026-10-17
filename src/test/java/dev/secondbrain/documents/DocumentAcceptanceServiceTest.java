package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventPublisher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class DocumentAcceptanceServiceTest {
    private static final byte[] CONTENT = "Small learning step".getBytes(StandardCharsets.UTF_8);
    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");

    @TempDir private Path storageDirectory;

    private DocumentUploadPolicy uploadPolicy;
    private OriginalFileStore originalFileStore;
    private JdbcDocumentRepository documentRepository;
    private EventPublisher eventPublisher;
    private DocumentAcceptanceService service;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DocumentTelemetry telemetry =
            new DocumentTelemetry(meters, ObservationRegistry.NOOP);

    @AfterEach
    void closeMeters() {
        meters.close();
    }

    @BeforeEach
    void setUp() {
        uploadPolicy = new DocumentUploadPolicy(DataSize.ofMegabytes(10));
        originalFileStore = new OriginalFileStore(storageDirectory.toString(), uploadPolicy);
        documentRepository = mock(JdbcDocumentRepository.class);
        eventPublisher = mock(EventPublisher.class);
        service =
                new DocumentAcceptanceService(
                        uploadPolicy,
                        originalFileStore,
                        documentRepository,
                        eventPublisher,
                        directTransactions(),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        telemetry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"meeting.md", "../outside.txt", "..\\outside.md"})
    void storesOriginalAndWritesMetadataAndAcceptedEventInOneTransaction(String originalName)
            throws IOException {
        var correlationId = CORRELATION_ID;
        var document =
                service.accept(
                        originalName,
                        CONTENT.length,
                        new ByteArrayInputStream(CONTENT),
                        correlationId);

        var documentCaptor = ArgumentCaptor.forClass(DocumentMetadata.class);
        verify(documentRepository).insert(documentCaptor.capture());
        assertThat(documentCaptor.getValue().documentId()).isEqualTo(document.documentId());
        assertThat(documentCaptor.getValue().originalName()).isEqualTo(originalName);
        assertThat(document.state()).isEqualTo(ExtractionState.QUEUED);
        var eventCaptor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(eventPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo(DocumentAcceptedEvent.TYPE);
        assertThat(eventCaptor.getValue().documentId()).isEqualTo(document.documentId());
        assertThat(eventCaptor.getValue().correlationId()).isEqualTo(correlationId);
        assertThat(
                        Files.readAllBytes(
                                storageDirectory.resolve(
                                        documentCaptor.getValue().storageId() + ".original")))
                .containsExactly(CONTENT);
        try (var files = Files.list(storageDirectory)) {
            assertThat(files)
                    .containsExactly(
                            storageDirectory.resolve(
                                    documentCaptor.getValue().storageId() + ".original"));
        }
    }

    @Test
    void removesStoredOriginalWhenTheDatabaseWriteFails() throws IOException {
        doThrow(new IllegalStateException("database unavailable"))
                .when(documentRepository)
                .insert(any(DocumentMetadata.class));

        assertThatThrownBy(
                        () ->
                                service.accept(
                                        "meeting.txt",
                                        CONTENT.length,
                                        new ByteArrayInputStream(CONTENT),
                                        CORRELATION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        try (var files = Files.list(storageDirectory)) {
            assertThat(files).isEmpty();
        }
        verify(eventPublisher, never()).publish(any(EventEnvelope.class));
    }

    @Test
    void rejectsUnsupportedFileBeforeStoringOrPersistingIt() {
        assertThatThrownBy(
                        () ->
                                service.accept(
                                        "meeting.pdf",
                                        CONTENT.length,
                                        new ByteArrayInputStream(CONTENT),
                                        CORRELATION_ID))
                .isInstanceOf(UploadRejectedException.class);

        verifyNoInteractions(documentRepository, eventPublisher);
        assertThat(Files.exists(storageDirectory.resolve("anything.original"))).isFalse();
    }

    @Test
    void keepsCleanupProtectionUntilTheTransactionHasFinished() throws IOException {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var settings =
                new OriginalCleanupProperties(Duration.ofHours(1), Duration.ofMinutes(1), 100);
        try (var cleanup =
                new OriginalFileReconciler(
                        originalFileStore, documentRepository, settings, clock, telemetry)) {
            var transactions =
                    new TransactionOperations() {
                        @Override
                        public <T> T execute(TransactionCallback<T> action) {
                            var result = action.doInTransaction(mock(TransactionStatus.class));
                            // The callback returned, but the transaction has not committed yet.
                            assertThatCode(
                                            () -> {
                                                Path stored;
                                                try (var files = Files.list(storageDirectory)) {
                                                    stored = files.findFirst().orElseThrow();
                                                }
                                                Files.setLastModifiedTime(
                                                        stored,
                                                        FileTime.from(
                                                                NOW.minus(Duration.ofHours(2))));
                                                assertThat(cleanup.reconcileBatch().busy())
                                                        .isEqualTo(1);
                                                assertThat(stored).exists();
                                            })
                                    .doesNotThrowAnyException();
                            return result;
                        }
                    };
            var protectedService =
                    new DocumentAcceptanceService(
                            uploadPolicy,
                            originalFileStore,
                            documentRepository,
                            eventPublisher,
                            transactions,
                            clock,
                            telemetry);
            protectedService.accept(
                    "note.md", CONTENT.length, new ByteArrayInputStream(CONTENT), CORRELATION_ID);
            // The test repository owns no real rows; removing this orphan proves the scope closed.
            assertThat(cleanup.reconcileBatch().deleted()).isEqualTo(1);
        }
    }

    private static TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(mock(TransactionStatus.class));
            }
        };
    }
}
