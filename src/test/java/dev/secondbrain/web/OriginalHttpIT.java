package dev.secondbrain.web;

import static dev.secondbrain.TestHttp.client;
import static dev.secondbrain.TestHttp.get;
import static dev.secondbrain.TestHttp.origin;
import static dev.secondbrain.TestHttp.post;
import static dev.secondbrain.TestHttp.upload;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.TestDatabase;
import dev.secondbrain.documents.PdfFixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/** Real application: stored originals are streamed safely and only while visible. */
class OriginalHttpIT {
    @TempDir private Path storage;

    @Test
    void streamsPdfsInlineAndEverythingElseAsDownloadsNeverAsHtml() throws Exception {
        try (var postgres = TestDatabase.create();
                var context = start(postgres);
                var client = client()) {
            var origin = origin(context);
            var pdfBytes = PdfFixtures.text("First page", "Second page");
            var pdf = upload(client, origin, "notes.pdf", pdfBytes);
            var html =
                    upload(
                            client,
                            origin,
                            "page.html",
                            "<html><body><script>alert(1)</script><p>Hi</p></body></html>"
                                    .getBytes(StandardCharsets.UTF_8));

            var inline = bytes(client, origin.resolve("/documents/" + pdf + "/original"));
            assertThat(inline.statusCode()).isEqualTo(200);
            assertThat(inline.body()).isEqualTo(pdfBytes);
            assertThat(inline.headers().firstValue("Content-Type")).hasValue("application/pdf");
            assertThat(inline.headers().firstValue("Content-Disposition"))
                    .hasValueSatisfying(value -> assertThat(value).startsWith("inline"));
            assertThat(inline.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
            assertThat(inline.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(inline.headers().firstValue("Content-Security-Policy"))
                    .hasValue("frame-ancestors 'self'");

            var download =
                    bytes(client, origin.resolve("/documents/" + pdf + "/original?download=1"));
            assertThat(download.headers().firstValue("Content-Disposition"))
                    .hasValueSatisfying(value -> assertThat(value).startsWith("attachment"));

            // An imported or uploaded HTML page is only ever a download.
            var page = bytes(client, origin.resolve("/documents/" + html + "/original"));
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.headers().firstValue("Content-Type"))
                    .hasValue("application/octet-stream");
            assertThat(page.headers().firstValue("Content-Disposition"))
                    .hasValueSatisfying(value -> assertThat(value).startsWith("attachment"));

            // The document page opens a PDF on its original and offers the other views.
            var documentPage = get(client, origin, "/documents/" + pdf).body();
            assertThat(documentPage)
                    .contains(
                            "class=\"original-frame\"",
                            "src=\"/documents/" + pdf + "/original#view=FitH\"",
                            "aria-current=\"page\">Original</a>",
                            "Reading view",
                            "Download original");

            assertThat(
                            bytes(
                                            client,
                                            origin.resolve(
                                                    "/documents/"
                                                            + UUID.randomUUID()
                                                            + "/original"))
                                    .statusCode())
                    .isEqualTo(404);
            post(client, origin, "/documents/" + pdf + "/delete", "");
            assertThat(
                            bytes(client, origin.resolve("/documents/" + pdf + "/original"))
                                    .statusCode())
                    .isEqualTo(404);
        }
    }

    private static HttpResponse<byte[]> bytes(java.net.http.HttpClient client, java.net.URI uri)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private ConfigurableApplicationContext start(TestDatabase postgres) {
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.SERVLET)
                .registerShutdownHook(false)
                .run(
                        postgres.arguments(
                                "--secondbrain.telemetry.otlp-endpoint=",
                                "--server.address=127.0.0.1",
                                "--server.port=0",
                                "--secondbrain.events.polling-enabled=false",
                                "--secondbrain.documents.storage-directory=" + storage));
    }
}
