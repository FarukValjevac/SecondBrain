package dev.secondbrain.web;

import static dev.secondbrain.TestHttp.client;
import static dev.secondbrain.TestHttp.get;
import static dev.secondbrain.TestHttp.origin;
import static dev.secondbrain.TestHttp.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.secondbrain.TestApplication;
import dev.secondbrain.TestDatabase;
import dev.secondbrain.documents.PdfFixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Real application and PostgreSQL: long documents are read one section at a time. */
class DocumentSectionsIT {
    @TempDir private Path storage;

    @Test
    void longTextAndPdfsOpenOneSectionAndSourceLinksFindTheirSection() throws Exception {
        try (var postgres = TestDatabase.create();
                var context = start(postgres);
                var client = client()) {
            var origin = origin(context);
            var jdbc = context.getBean(JdbcClient.class);

            // 2,500 lines without pages: three sections of up to 1,000 lines.
            var text =
                    IntStream.rangeClosed(1, 2500)
                            .mapToObj(line -> "Line marker " + line)
                            .collect(Collectors.joining("\n"));
            var notes = upload(client, origin, "long.md", text.getBytes(StandardCharsets.UTF_8));
            awaitExtracted(jdbc, notes);
            var first = get(client, origin, "/documents/" + notes + "?view=lines").body();
            assertThat(first)
                    .contains("Part 1 of 3", "Lines 1–1000", "Lines 2001–2500", "Line marker 1000")
                    .doesNotContain("Line marker 1001<");
            var second = get(client, origin, "/documents/" + notes + "?view=reading&part=2").body();
            assertThat(second).contains("Part 2 of 3", "Line marker 1001", "Line marker 2000");
            assertThat(second).doesNotContain("Line marker 999 ", "Line marker 2001");
            // A source link opens the section holding its lines.
            var cited =
                    get(client, origin, "/documents/" + notes + "?view=reading&from=2400&to=2401")
                            .body();
            assertThat(cited).contains("Part 3 of 3", "id=\"line-2400\"", "cited");

            // A 45-page PDF: sections of 20 pages, by page.
            var pages =
                    IntStream.rangeClosed(1, 45)
                            .mapToObj(page -> "Page text " + page)
                            .toArray(String[]::new);
            var book = upload(client, origin, "book.pdf", PdfFixtures.text(pages));
            awaitExtracted(jdbc, book);
            var lastPart = get(client, origin, "/documents/" + book + "?view=lines&part=3").body();
            assertThat(lastPart)
                    .contains("Part 3 of 3", "Pages 41–45", "Page text 41", "Page text 45")
                    .doesNotContain("Page text 40<");
            // Out-of-range parts show the nearest one instead of failing.
            assertThat(get(client, origin, "/documents/" + book + "?view=lines&part=99").body())
                    .contains("Part 3 of 3");
            // Short documents show no section navigation at all.
            var small =
                    upload(client, origin, "small.md", "Hello".getBytes(StandardCharsets.UTF_8));
            awaitExtracted(jdbc, small);
            assertThat(get(client, origin, "/documents/" + small + "?view=reading").body())
                    .doesNotContain("section-nav");
        }
    }

    private static void awaitExtracted(JdbcClient jdbc, UUID id) {
        await().atMost(Duration.ofSeconds(30))
                .until(
                        () ->
                                "EXTRACTED"
                                        .equals(
                                                jdbc.sql(
                                                                "SELECT extraction_state FROM documents WHERE document_id = :id")
                                                        .param("id", id)
                                                        .query(String.class)
                                                        .single()));
    }

    private ConfigurableApplicationContext start(TestDatabase postgres) {
        return TestApplication.run(
                postgres.arguments(
                        "--secondbrain.telemetry.otlp-endpoint=",
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--secondbrain.documents.indexing.enabled=false",
                        "--secondbrain.events.polling-enabled=true",
                        "--secondbrain.events.poll-interval=50ms",
                        "--secondbrain.documents.storage-directory=" + storage));
    }
}
