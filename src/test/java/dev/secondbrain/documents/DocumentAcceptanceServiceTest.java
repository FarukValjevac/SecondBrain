package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventPublisher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.time.Clock;
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
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void storesOriginalAndWritesMetadataAndAcceptedEventInOneTransaction() throws IOException {
        var correlationId = CORRELATION_ID;
        var document =
                service.accept(
                        "meeting.md",
                        CONTENT.length,
                        new ByteArrayInputStream(CONTENT),
                        correlationId);

        var documentCaptor = ArgumentCaptor.forClass(DocumentMetadata.class);
        verify(documentRepository).insert(documentCaptor.capture());
        assertThat(documentCaptor.getValue().documentId()).isEqualTo(document.documentId());
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

    private static TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(mock(TransactionStatus.class));
            }
        };
    }
}
