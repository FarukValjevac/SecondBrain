package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.util.unit.DataSize;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Checks release gold spans against actual storage/extraction/splitting, with no database/model.
 */
class ReleaseReferencePreparationTest {
    private static final String ROOT = "/retrieval/reference-v2/";
    private static final Instant AT = Instant.parse("2030-01-01T00:00:00Z");
    @TempDir Path directory;

    static Stream<JsonNode> documents() throws Exception {
        return java.util.stream.StreamSupport.stream(
                collection().path("documents").spliterator(), false);
    }

    @ParameterizedTest(name = "reference extraction {index}")
    @MethodSource("documents")
    void goldLocationsAndStablePassageRecipeMatchRealPreparation(JsonNode document)
            throws Exception {
        var collection = collection();
        String label = document.path("id").asString();
        String file = document.path("file").asString();
        var clock = Clock.fixed(AT, ZoneOffset.UTC);
        var originals =
                new OriginalFileStore(
                        directory.toString(), new DocumentUploadPolicy(DataSize.ofKilobytes(64)));
        byte[] bytes;
        try (var input = getClass().getResourceAsStream(ROOT + file)) {
            bytes = input.readAllBytes();
        }
        var id =
                UUID.nameUUIDFromBytes(
                        ("secondbrain:reference:java-study-v2:" + label)
                                .getBytes(StandardCharsets.UTF_8));
        var type =
                file.endsWith(".pdf")
                        ? DocumentType.PDF
                        : file.endsWith(".md") ? DocumentType.MARKDOWN : DocumentType.TXT;
        try (var staged = originals.stage(new ByteArrayInputStream(bytes))) {
            var stored = staged.original();
            assertThat(stored.sha256()).isEqualTo(document.path("sha256").asString());
            var metadata =
                    new DocumentMetadata(
                            id,
                            file,
                            type,
                            stored.storageId(),
                            stored.sha256(),
                            stored.sizeInBytes(),
                            1,
                            ExtractionState.QUEUED,
                            AT,
                            AT);
            var extraction =
                    new DocumentTextExtractionService(
                                    originals,
                                    new Utf8TextExtractor(65_536),
                                    new PdfTextExtractor(65_536, clock),
                                    clock)
                            .extract(metadata);
            var version = new IndexingVersion(id, 1, 1, AT);
            var prepared = new PassageSplitter().split(version, extraction);
            assertThat(prepared.configurationVersion()).isEqualTo(1);
            assertThat(prepared.passages()).hasSize(1);
            var passage = StoredPassage.from(version, prepared.passages().getFirst());
            var expectedId =
                    UUID.nameUUIDFromBytes(
                            ("secondbrain:passage:v1:" + id + ":1:1")
                                    .getBytes(StandardCharsets.UTF_8));
            assertThat(passage.passageId()).isEqualTo(expectedId);
            assertThat(
                            java.util.HexFormat.of()
                                    .formatHex(
                                            java.security.MessageDigest.getInstance("SHA-256")
                                                    .digest(
                                                            passage.text()
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8))))
                    .isEqualTo(document.path("passageSha256").asString());
            assertThat(passage.location().pageNumber())
                    .isEqualTo(type == DocumentType.PDF ? OptionalInt.of(1) : OptionalInt.empty());
            for (var source : collection.path("sources")) {
                if (!source.path("document").asString().equals(label)) continue;
                int start = source.path("startLine").asInt();
                int end = source.path("endLine").asInt();
                String actual =
                        String.join(
                                "\n",
                                extraction.lines().subList(start - 1, end).stream()
                                        .map(ExtractedText.Line::text)
                                        .toList());
                assertThat(actual).isEqualTo(source.path("quote").asString());
                assertThat(passage.text()).contains(actual);
                assertThat(passage.location().startLine()).isLessThanOrEqualTo(start);
                assertThat(passage.location().endLine()).isGreaterThanOrEqualTo(end);
            }
        }
    }

    private static JsonNode collection() throws Exception {
        try (var input =
                ReleaseReferencePreparationTest.class.getResourceAsStream(
                        ROOT + "collection.json")) {
            return JsonMapper.builder().build().readTree(input);
        }
    }
}
