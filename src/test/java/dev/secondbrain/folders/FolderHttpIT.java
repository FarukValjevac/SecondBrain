package dev.secondbrain.folders;

import static dev.secondbrain.TestHttp.client;
import static dev.secondbrain.TestHttp.encode;
import static dev.secondbrain.TestHttp.get;
import static dev.secondbrain.TestHttp.origin;
import static dev.secondbrain.TestHttp.post;
import static dev.secondbrain.TestHttp.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.TestDatabase;
import dev.secondbrain.documents.DocumentAcceptanceService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Real application and PostgreSQL: folders, filing on upload, moving and deleting. */
@ExtendWith(OutputCaptureExtension.class)
class FolderHttpIT {
    @TempDir private Path storage;

    @Test
    void filesDocumentsIntoNestedFoldersAndKeepsThemWhenAFolderIsDeleted(CapturedOutput output)
            throws Exception {
        try (var postgres = TestDatabase.create();
                var context = start(postgres);
                var client = client()) {
            var origin = origin(context);
            var jdbc = context.getBean(JdbcClient.class);

            // A document uploaded before any folder exists stays unfiled.
            var unfiled =
                    upload(client, origin, "old.md", "Old notes".getBytes(StandardCharsets.UTF_8));
            assertThat(folderOf(jdbc, unfiled)).isEmpty();

            var sport = create(client, origin, "Sport-private-name", "", "green");
            var running = create(client, origin, "Running", sport.toString(), "teal");
            // Names are unique among siblings, ignoring case.
            var duplicate =
                    post(
                            client,
                            origin,
                            "/folders",
                            "name=" + encode("sport-PRIVATE-name") + "&parent=&color=blue");
            assertThat(duplicate.statusCode()).isEqualTo(303);
            assertThat(duplicate.headers().firstValue("Location"))
                    .hasValueSatisfying(l -> assertThat(l).endsWith("/folders"));
            assertThat(jdbc.sql("SELECT count(*) FROM folders").query(Integer.class).single())
                    .isEqualTo(2);

            var library = get(client, origin, "/library").body();
            assertThat(library)
                    .contains(
                            "id=\"upload-folder\"",
                            "id=\"import-folder\"",
                            "Sport-private-name › Running");

            // A browser upload into the subfolder returns to that folder.
            var response = browserUpload(client, origin, "run.md", "Intervals", running);
            assertThat(response.statusCode()).isEqualTo(303);
            assertThat(response.headers().firstValue("Location"))
                    .hasValueSatisfying(l -> assertThat(l).endsWith("/library?folder=" + running));
            var filed =
                    jdbc.sql("SELECT document_id FROM documents WHERE original_name = 'run.md'")
                            .query(UUID.class)
                            .single();
            assertThat(folderOf(jdbc, filed)).contains(running);
            assertThat(get(client, origin, "/documents/" + filed).body())
                    .contains("folder-chip", "Sport-private-name › Running", "id=\"move-folder\"");

            // Moving: to Unfiled and back.
            assertThat(
                            post(client, origin, "/documents/" + filed + "/folder", "folder=")
                                    .statusCode())
                    .isEqualTo(303);
            assertThat(folderOf(jdbc, filed)).isEmpty();
            post(client, origin, "/documents/" + filed + "/folder", "folder=" + running);
            assertThat(folderOf(jdbc, filed)).contains(running);

            // Folder scope helper includes subfolders.
            var service = context.getBean(FolderService.class);
            assertThat(service.documentsWithin(sport, 100)).containsExactly(filed);

            // Deleting Sport keeps Running (now top level) and its document.
            assertThat(get(client, origin, "/folders/" + sport + "/delete").body())
                    .contains("No document is deleted.");
            var deleted = post(client, origin, "/folders/" + sport + "/delete", "");
            assertThat(deleted.statusCode()).isEqualTo(303);
            assertThat(service.find(sport)).isEmpty();
            var lifted = service.find(running).orElseThrow();
            assertThat(lifted.parentId()).isEmpty();
            assertThat(lifted.depth()).isEqualTo(1);
            assertThat(folderOf(jdbc, filed)).contains(running);

            // Nesting stops at three levels.
            var b = service.create("B", Optional.of(running), FolderColor.AMBER);
            var c = service.create("C", Optional.of(b.folderId()), FolderColor.AMBER);
            assertThatThrownBy(
                            () -> service.create("D", Optional.of(c.folderId()), FolderColor.AMBER))
                    .isInstanceOfSatisfying(
                            FolderException.class,
                            failure ->
                                    assertThat(failure.reason())
                                            .isEqualTo(FolderException.Reason.TOO_DEEP));

            // A folder that no longer exists leaves a new document unfiled instead of failing.
            var acceptance = context.getBean(DocumentAcceptanceService.class);
            var bytes = "Gone".getBytes(StandardCharsets.UTF_8);
            var receipt =
                    acceptance.accept(
                            "gone.md",
                            bytes.length,
                            new ByteArrayInputStream(bytes),
                            UUID.randomUUID(),
                            Optional.of(UUID.randomUUID()));
            assertThat(folderOf(jdbc, receipt.documentId())).isEmpty();

            assertThat(get(client, origin, "/folders").body())
                    .contains("Your folders", "Running", "Add subfolder", "color-picker");
        }
        assertThat(output).doesNotContain("Sport-private-name");
    }

    private static UUID create(
            HttpClient client, URI origin, String name, String parent, String color)
            throws Exception {
        var response =
                post(
                        client,
                        origin,
                        "/folders",
                        "name=" + encode(name) + "&parent=" + parent + "&color=" + color);
        assertThat(response.statusCode()).isEqualTo(303);
        var location = response.headers().firstValue("Location").orElseThrow();
        assertThat(location).contains("/library?folder=");
        return UUID.fromString(location.substring(location.indexOf("folder=") + 7));
    }

    private static Optional<UUID> folderOf(JdbcClient jdbc, UUID document) {
        return jdbc.sql(
                        "SELECT folder_id FROM documents WHERE document_id = :id AND folder_id IS NOT NULL")
                .param("id", document)
                .query(UUID.class)
                .optional();
    }

    private static HttpResponse<String> browserUpload(
            HttpClient client, URI origin, String name, String text, UUID folder) throws Exception {
        var boundary = "folder-test-boundary";
        var body = new ByteArrayOutputStream();
        body.writeBytes(
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\"folder\"\r\n\r\n"
                                + folder
                                + "\r\n"
                                + "--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                                + name
                                + "\"\r\nContent-Type: text/markdown\r\n\r\n"
                                + text
                                + "\r\n--"
                                + boundary
                                + "--\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        return client.send(
                HttpRequest.newBuilder(origin.resolve("/documents/upload"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("Origin", "null")
                        .header("Sec-Fetch-Site", "same-origin")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
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
