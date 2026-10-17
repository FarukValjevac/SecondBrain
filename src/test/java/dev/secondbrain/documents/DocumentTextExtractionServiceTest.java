package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.secondbrain.documents.TextExtractionException.Reason;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class DocumentTextExtractionServiceTest {
    private static final Instant EXTRACTED_AT = Instant.parse("2026-01-01T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(EXTRACTED_AT, ZoneOffset.UTC);
    @TempDir Path temporaryDirectory;

    @ParameterizedTest
    @EnumSource(DocumentType.class)
    void extractsTheStoredOriginalWithDocumentIdentityVersionAndCompletionTime(DocumentType type)
            throws IOException {
        var store = store();
        var bytes = "# Café 📚\r\n\r\nStudy\n".getBytes(StandardCharsets.UTF_8);
        var document = storeDocument(store, bytes, type);
        var service = new DocumentTextExtractionService(store, new Utf8TextExtractor(1024), CLOCK);

        var text = service.extract(document);

        assertThat(text.documentId()).isEqualTo(document.documentId());
        assertThat(text.processingVersion()).isEqualTo(7);
        assertThat(text.extractedAt()).isEqualTo(EXTRACTED_AT);
        assertThat(text.lines())
                .containsExactly(
                        new ExtractedText.Line(1, "# Café 📚"),
                        new ExtractedText.Line(2, ""),
                        new ExtractedText.Line(3, "Study"),
                        new ExtractedText.Line(4, ""));
        assertThat(Files.readAllBytes(originalPath(document))).containsExactly(bytes);
    }

    @Test
    void preservesAnInvalidOriginalAndItsSafeDecodingFailure() throws IOException {
        var store = store();
        var bytes = new byte[] {(byte) 0xC3, 0x28};
        var document = storeDocument(store, bytes, DocumentType.TXT);
        var service = new DocumentTextExtractionService(store, new Utf8TextExtractor(1024), CLOCK);

        assertThatThrownBy(() -> service.extract(document))
                .isInstanceOfSatisfying(
                        TextExtractionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.INVALID_ENCODING));
        assertThat(Files.readAllBytes(originalPath(document))).containsExactly(bytes);
    }

    @Test
    void translatesAMissingOriginalWithoutExposingItsPathOrOriginalName() {
        var document =
                document(
                        new OriginalFileStore.StoredOriginal(UUID.randomUUID(), 4, "a".repeat(64)),
                        DocumentType.TXT);
        var service =
                new DocumentTextExtractionService(store(), new Utf8TextExtractor(1024), CLOCK);

        assertThatThrownBy(() -> service.extract(document))
                .isInstanceOfSatisfying(
                        TextExtractionException.class,
                        failure ->
                                assertThat(failure.reason()).isEqualTo(Reason.SOURCE_UNAVAILABLE))
                .hasCauseInstanceOf(NoSuchFileException.class)
                .hasMessage("The original document could not be read.")
                .hasMessageNotContaining(temporaryDirectory.toString())
                .hasMessageNotContaining(document.originalName());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesTheOriginalOnSuccessAndDecodingFailure(boolean invalid) throws IOException {
        var store = mock(OriginalFileStore.class);
        var document =
                document(
                        new OriginalFileStore.StoredOriginal(UUID.randomUUID(), 4, "a".repeat(64)),
                        DocumentType.TXT);
        var input =
                spy(
                        new ByteArrayInputStream(
                                (invalid ? "\0" : "Note").getBytes(StandardCharsets.UTF_8)));
        when(store.open(document.storageId())).thenReturn(input);
        var service = new DocumentTextExtractionService(store, new Utf8TextExtractor(1024), CLOCK);

        if (invalid) {
            assertThatThrownBy(() -> service.extract(document))
                    .isInstanceOf(TextExtractionException.class);
        } else {
            service.extract(document);
        }

        verify(input).close();
        verify(store).open(document.storageId());
    }

    @Test
    void doesNotReturnSuccessfulTextWhenClosingTheOriginalFails() throws IOException {
        var store = mock(OriginalFileStore.class);
        var document =
                document(
                        new OriginalFileStore.StoredOriginal(UUID.randomUUID(), 4, "a".repeat(64)),
                        DocumentType.TXT);
        var input = spy(new ByteArrayInputStream("Note".getBytes(StandardCharsets.UTF_8)));
        var unavailable = new IOException("private close failure");
        when(store.open(document.storageId())).thenReturn(input);
        doThrow(unavailable).when(input).close();
        var service = new DocumentTextExtractionService(store, new Utf8TextExtractor(1024), CLOCK);

        assertThatThrownBy(() -> service.extract(document))
                .isInstanceOfSatisfying(
                        TextExtractionException.class,
                        failure ->
                                assertThat(failure.reason()).isEqualTo(Reason.SOURCE_UNAVAILABLE))
                .hasCause(unavailable)
                .hasMessage("The original document could not be read.");
    }

    private OriginalFileStore store() {
        return new OriginalFileStore(
                temporaryDirectory.toString(), new DocumentUploadPolicy(DataSize.ofBytes(1024)));
    }

    private static DocumentMetadata storeDocument(
            OriginalFileStore store, byte[] bytes, DocumentType type) throws IOException {
        try (var source = new ByteArrayInputStream(bytes);
                var staged = store.stage(source)) {
            return document(staged.original(), type);
        }
    }

    private static DocumentMetadata document(
            OriginalFileStore.StoredOriginal original, DocumentType type) {
        return new DocumentMetadata(
                UUID.randomUUID(),
                "../private-study-notes",
                type,
                original.storageId(),
                original.sha256(),
                original.sizeInBytes(),
                7,
                ExtractionState.QUEUED,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private Path originalPath(DocumentMetadata document) {
        return temporaryDirectory.resolve(document.storageId() + ".original");
    }
}
