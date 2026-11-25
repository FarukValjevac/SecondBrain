package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.TestDatabase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/** Real application: an identical upload is reported with the earlier copy, never blocked. */
class DuplicateUploadIT {
    @TempDir private Path storage;

    @Test
    void reportsAnIdenticalUploadAndIgnoresDifferentOrDeletedFiles() throws Exception {
        try (var postgres = TestDatabase.create();
                var context = start(postgres)) {
            var origin =
                    URI.create(
                            "http://127.0.0.1:"
                                    + context.getEnvironment()
                                            .getRequiredProperty("local.server.port"));
            var client =
                    HttpClient.newBuilder()
                            .cookieHandler(new CookieManager())
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .connectTimeout(Duration.ofSeconds(5))
                            .build();
            var first = upload(client, origin, "lecture.md", "Same content");
            assertThat(first).doesNotContain("Already in your library");

            var second = upload(client, origin, "lecture-copy.md", "Same content");
            var jdbc = context.getBean(JdbcClient.class);
            var ids =
                    jdbc.sql("SELECT document_id FROM documents ORDER BY created_at")
                            .query(UUID.class)
                            .list();
            assertThat(ids).hasSize(2);
            assertThat(second)
                    .contains(
                            "Already in your library",
                            "href=\"/documents/" + ids.get(0) + "\"",
                            ">lecture.md<",
                            "href=\"/documents/" + ids.get(1) + "/delete\"",
                            "Delete the new copy");

            assertThat(upload(client, origin, "other.md", "Different content"))
                    .doesNotContain("Already in your library");

            // A deleted copy no longer counts as a duplicate.
            jdbc.sql("UPDATE documents SET deleted_at = now() WHERE document_id IN (:ids)")
                    .param("ids", ids)
                    .update();
            assertThat(upload(client, origin, "again.md", "Same content"))
                    .doesNotContain("Already in your library");
        }
    }

    private static String upload(HttpClient client, URI origin, String name, String text)
            throws Exception {
        var response = dev.secondbrain.TestHttp.browserUpload(client, origin, name, text);
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private ConfigurableApplicationContext start(TestDatabase postgres) {
        return dev.secondbrain.TestApplication.run(
                postgres.arguments(
                        "--secondbrain.telemetry.otlp-endpoint=",
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--secondbrain.events.polling-enabled=false",
                        "--secondbrain.documents.storage-directory=" + storage));
    }
}
