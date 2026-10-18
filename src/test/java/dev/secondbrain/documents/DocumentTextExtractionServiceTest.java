package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.secondbrain.documents.TextExtractionException.Reason;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

class DocumentTextExtractionServiceTest {
    private static final Instant EXTRACTED_AT = Instant.parse("2026-01-01T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(EXTRACTED_AT, ZoneOffset.UTC);
    @TempDir Path temporaryDirectory;

    @ParameterizedTest
    @EnumSource(
            value = DocumentType.class,
            names = {"TXT", "MARKDOWN"})
    void extractsTheStoredOriginalWithDocumentIdentityVersionAndCompletionTime(DocumentType type)
            throws IOException {
        var store = store();
        var bytes = "# Café 📚\r\n\r\nStudy\n".getBytes(StandardCharsets.UTF_8);
        var document = storeDocument(store, bytes, type);
        var service =
                new DocumentTextExtractionService(
                        store,
                        new Utf8TextExtractor(1024),
                        new PdfTextExtractor(1024, CLOCK),
                        CLOCK);

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
        var service =
                new DocumentTextExtractionService(
                        store,
                        new Utf8TextExtractor(1024),
                        new PdfTextExtractor(1024, CLOCK),
                        CLOCK);

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
                new DocumentTextExtractionService(
                        store(),
                        new Utf8TextExtractor(1024),
                        new PdfTextExtractor(1024, CLOCK),
                        CLOCK);

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
        var service =
                new DocumentTextExtractionService(
                        store,
                        new Utf8TextExtractor(1024),
                        new PdfTextExtractor(1024, CLOCK),
                        CLOCK);

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
        var service =
                new DocumentTextExtractionService(
                        store,
                        new Utf8TextExtractor(1024),
                        new PdfTextExtractor(1024, CLOCK),
                        CLOCK);

        assertThatThrownBy(() -> service.extract(document))
                .isInstanceOfSatisfying(
                        TextExtractionException.class,
                        failure ->
                                assertThat(failure.reason()).isEqualTo(Reason.SOURCE_UNAVAILABLE))
                .hasCause(unavailable)
                .hasMessage("The original document could not be read.");
    }

    @Test
    void routesStoredPdfBytesThroughTheRealParserAndRetainsPhysicalPages() throws IOException {
        var store =
                new OriginalFileStore(
                        temporaryDirectory.toString(),
                        new DocumentUploadPolicy(DataSize.ofMegabytes(1)));
        var bytes = PdfFixtures.text("Café notes", "", "Final page");
        var document = storeDocument(store, bytes, DocumentType.PDF);
        var service =
                new DocumentTextExtractionService(
                        store,
                        new Utf8TextExtractor(1024),
                        new PdfTextExtractor(1024 * 1024, CLOCK),
                        CLOCK);

        var text = service.extract(document);

        assertThat(text.documentId()).isEqualTo(document.documentId());
        assertThat(text.processingVersion()).isEqualTo(7);
        assertThat(text.extractedAt()).isEqualTo(EXTRACTED_AT);
        assertThat(text.lines())
                .filteredOn(line -> !line.text().isBlank())
                .extracting(line -> line.pageNumber().orElseThrow())
                .containsExactly(1, 3);
        assertThat(text.lines())
                .anySatisfy(
                        line -> {
                            assertThat(line.pageNumber()).hasValue(2);
                            assertThat(line.text()).isEmpty();
                        });
        assertThat(Files.readAllBytes(originalPath(document))).containsExactly(bytes);
    }

    @Test
    void normalizesPdfLineSeparatorsWithoutLosingBlankPagesOrTrailingLines() throws IOException {
        var pdf = mock(PdfTextExtractor.class);
        var store = store();
        var document = storeDocument(store, new byte[] {1}, DocumentType.PDF);
        when(pdf.extract(any()))
                .thenReturn(
                        List.of(
                                new PdfTextExtractor.Page(1, "A\r\nB\rC\n"),
                                new PdfTextExtractor.Page(2, ""),
                                new PdfTextExtractor.Page(3, "D")));
        var service =
                new DocumentTextExtractionService(store, new Utf8TextExtractor(1024), pdf, CLOCK);

        assertThat(service.extract(document).lines())
                .containsExactly(
                        new ExtractedText.Line(1, "A", OptionalInt.of(1)),
                        new ExtractedText.Line(2, "B", OptionalInt.of(1)),
                        new ExtractedText.Line(3, "C", OptionalInt.of(1)),
                        new ExtractedText.Line(4, "", OptionalInt.of(1)),
                        new ExtractedText.Line(5, "", OptionalInt.of(2)),
                        new ExtractedText.Line(6, "D", OptionalInt.of(3)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void boundsPdfLinesAcrossPagesIncludingEmptyLines(boolean overflow) throws IOException {
        var pdf = mock(PdfTextExtractor.class);
        var store = store();
        var document = storeDocument(store, new byte[] {1}, DocumentType.PDF);
        when(pdf.extract(any()))
                .thenReturn(
                        List.of(
                                new PdfTextExtractor.Page(1, "\n".repeat(99_998) + "Text"),
                                new PdfTextExtractor.Page(2, overflow ? "\n" : "")));
        var service =
                new DocumentTextExtractionService(store, new Utf8TextExtractor(1024), pdf, CLOCK);

        if (overflow) {
            assertThatThrownBy(() -> service.extract(document))
                    .isInstanceOfSatisfying(
                            TextExtractionException.class,
                            failure ->
                                    assertThat(failure.reason()).isEqualTo(Reason.TOO_MANY_LINES));
        } else {
            assertThat(service.extract(document).lines())
                    .hasSize(100_000)
                    .last()
                    .isEqualTo(new ExtractedText.Line(100_000, "", OptionalInt.of(2)));
        }
    }

    @Test
    void rejectsNullCharactersBeforePdfTextCanReachPersistence() throws IOException {
        var pdf = mock(PdfTextExtractor.class);
        var store = store();
        var document = storeDocument(store, new byte[] {1}, DocumentType.PDF);
        when(pdf.extract(any())).thenReturn(List.of(new PdfTextExtractor.Page(1, "Private\0text")));

        assertThatThrownBy(
                        () ->
                                new DocumentTextExtractionService(
                                                store, new Utf8TextExtractor(1024), pdf, CLOCK)
                                        .extract(document))
                .isInstanceOfSatisfying(
                        TextExtractionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.INVALID_CONTENT))
                .hasMessageNotContaining("Private");
    }

    @ParameterizedTest
    @CsvSource({
        "SOURCE_UNAVAILABLE,SOURCE_UNAVAILABLE",
        "UNREADABLE,PDF_UNREADABLE",
        "ENCRYPTED,PDF_ENCRYPTED",
        "NO_TEXT,PDF_NO_TEXT",
        "INPUT_TOO_LARGE,INPUT_TOO_LARGE",
        "PAGE_LIMIT,PDF_PAGE_LIMIT",
        "TEXT_LIMIT,PDF_TEXT_LIMIT",
        "WORK_LIMIT,PDF_WORK_LIMIT",
        "TIME_LIMIT,PDF_TIME_LIMIT",
        "CANCELLED,CANCELLED"
    })
    void translatesPdfFailuresAndClosesTheOriginal(
            PdfExtractionException.Reason adapterReason, Reason expected) throws IOException {
        var store = mock(OriginalFileStore.class);
        var pdf = mock(PdfTextExtractor.class);
        var document =
                document(
                        new OriginalFileStore.StoredOriginal(UUID.randomUUID(), 1, "a".repeat(64)),
                        DocumentType.PDF);
        var input = spy(new ByteArrayInputStream(new byte[] {1}));
        when(store.open(document.storageId())).thenReturn(input);
        var cause =
                new PdfExtractionException(adapterReason, new IOException("Private PDF details"));
        when(pdf.extract(input)).thenThrow(cause);
        var service =
                new DocumentTextExtractionService(store, new Utf8TextExtractor(1024), pdf, CLOCK);

        assertThatThrownBy(() -> service.extract(document))
                .isInstanceOfSatisfying(
                        TextExtractionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(expected))
                .hasCause(cause)
                .hasMessageNotContaining("Private");
        verify(input).close();
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
