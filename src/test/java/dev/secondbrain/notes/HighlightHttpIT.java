package dev.secondbrain.notes;

import static dev.secondbrain.TestHttp.client;
import static dev.secondbrain.TestHttp.encode;
import static dev.secondbrain.TestHttp.get;
import static dev.secondbrain.TestHttp.origin;
import static dev.secondbrain.TestHttp.post;
import static dev.secondbrain.TestHttp.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.secondbrain.TestDatabase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/** Real application: highlights and notes from reading to the Notes page and back. */
@ExtendWith(OutputCaptureExtension.class)
class HighlightHttpIT {
    @TempDir private Path storage;

    @Test
    void highlightsLinesWithNotesAndHidesThemForChangedOrDeletedDocuments(CapturedOutput output)
            throws Exception {
        try (var postgres = TestDatabase.create();
                var context = start(postgres);
                var client = client()) {
            var origin = origin(context);
            var text =
                    "# Bindung\n\nBindung ist ein emotionales Band.\nSie entsteht früh.\n\nSchluss.\n";
            var id = upload(client, origin, "bindung.md", text.getBytes(StandardCharsets.UTF_8));
            var jdbc = context.getBean(JdbcClient.class);
            await().atMost(Duration.ofSeconds(20))
                    .until(
                            () ->
                                    "EXTRACTED"
                                            .equals(
                                                    jdbc.sql(
                                                                    "SELECT extraction_state FROM documents WHERE document_id = :id")
                                                            .param("id", id)
                                                            .query(String.class)
                                                            .single()));

            var saved =
                    post(
                            client,
                            origin,
                            "/documents/" + id + "/highlights",
                            "from=3&to=4&color=green&note="
                                    + encode("Private <b>note</b> zu Bindung"));
            assertThat(saved.statusCode()).isEqualTo(303);
            assertThat(saved.headers().firstValue("Location"))
                    .hasValueSatisfying(
                            l ->
                                    assertThat(l)
                                            .endsWith(
                                                    "/documents/"
                                                            + id
                                                            + "?view=reading&at=3#line-3"));

            var page = get(client, origin, "/documents/" + id + "?view=reading").body();
            assertThat(page)
                    .contains(
                            "id=\"line-3\" class=\"hl hl-green\"",
                            "id=\"line-4\" class=\"hl hl-green\"",
                            "Highlights &amp; notes",
                            // The quote comes from the stored lines.
                            "Bindung ist ein emotionales Band. Sie entsteht früh.",
                            "Private &lt;b&gt;note&lt;/b&gt; zu Bindung",
                            "id=\"highlight-toolbar\"",
                            "src=\"/js/reading-")
                    .doesNotContain("id=\"line-1\" class=\"hl", "<b>note</b>");

            var notes = get(client, origin, "/notes").body();
            assertThat(notes).contains("bindung.md", "Private &lt;b&gt;note&lt;/b&gt;");
            assertThat(get(client, origin, "/notes?q=" + encode("emotional")).body())
                    .contains("bindung.md");
            assertThat(get(client, origin, "/notes?q=" + encode("nichts-passt")).body())
                    .contains("No notes match this filter.");

            var highlight =
                    jdbc.sql("SELECT highlight_id FROM document_highlights")
                            .query(UUID.class)
                            .single();
            assertThat(
                            post(
                                            client,
                                            origin,
                                            "/highlights/" + highlight,
                                            "note=Neu&color=pink&return=notes")
                                    .headers()
                                    .firstValue("Location"))
                    .hasValueSatisfying(l -> assertThat(l).endsWith("/notes"));
            assertThat(get(client, origin, "/documents/" + id + "?view=reading").body())
                    .contains("class=\"hl hl-pink\"", ">Neu<");

            // Invalid ranges are refused; nothing is stored.
            assertThat(
                            post(
                                            client,
                                            origin,
                                            "/documents/" + id + "/highlights",
                                            "from=4&to=2&color=yellow")
                                    .statusCode())
                    .isEqualTo(400);

            // Reprocessing changes line numbers: highlights of the old text are hidden.
            jdbc.sql(
                            "UPDATE documents SET processing_version = processing_version + 1 WHERE document_id = :id")
                    .param("id", id)
                    .update();
            assertThat(get(client, origin, "/notes").body()).doesNotContain("bindung.md");
            jdbc.sql(
                            "UPDATE documents SET processing_version = processing_version - 1 WHERE document_id = :id")
                    .param("id", id)
                    .update();
            assertThat(
                            post(client, origin, "/highlights/" + highlight + "/delete", "")
                                    .statusCode())
                    .isEqualTo(303);
            assertThat(get(client, origin, "/notes").body()).contains("No highlights yet.");

            // A deleted document's highlights disappear with it.
            post(client, origin, "/documents/" + id + "/highlights", "from=3&to=3&color=blue");
            post(client, origin, "/documents/" + id + "/delete", "");
            assertThat(get(client, origin, "/notes").body()).doesNotContain("bindung.md");
        }
        assertThat(output).doesNotContain("Private <b>note</b>", "emotionales Band");
    }

    private ConfigurableApplicationContext start(TestDatabase postgres) {
        return dev.secondbrain.TestApplication.run(
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
