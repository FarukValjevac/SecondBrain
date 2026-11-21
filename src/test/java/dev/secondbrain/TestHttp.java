package dev.secondbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/** Real-HTTP helpers for tests that start the full application on a random local port. */
public final class TestHttp {
    private TestHttp() {}

    /** Redirects are not followed, so tests can assert the application's 303 responses. */
    public static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static URI origin(ConfigurableApplicationContext context) {
        return URI.create(
                "http://127.0.0.1:"
                        + context.getEnvironment()
                                .getRequiredProperty("local.server.port", Integer.class));
    }

    public static HttpResponse<String> get(HttpClient client, URI origin, String path)
            throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(origin.resolve(path))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public static HttpResponse<String> post(HttpClient client, URI origin, String path, String body)
            throws IOException, InterruptedException {
        return client.send(formRequest(origin, path, body), HttpResponse.BodyHandlers.ofString());
    }

    /** A form post with the headers a browser sends from a page with no-referrer policy. */
    public static HttpRequest formRequest(URI origin, String path, String body) {
        return HttpRequest.newBuilder(origin.resolve(path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "null")
                .header("Sec-Fetch-Site", "same-origin")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    public static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    /** Uploads through the JSON endpoint and returns the accepted document's ID. */
    public static UUID upload(HttpClient client, URI origin, String filename, byte[] data)
            throws IOException, InterruptedException {
        var boundary = "test-upload-boundary";
        var body = new ByteArrayOutputStream();
        body.writeBytes(
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                                + filename
                                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(data);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var response =
                client.send(
                        HttpRequest.newBuilder(origin.resolve("/documents"))
                                .timeout(Duration.ofSeconds(15))
                                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return UUID.fromString(
                new JsonMapper().readTree(response.body()).path("documentId").stringValue());
    }

    /** Waits until this many documents have an active, searchable index. */
    public static void ready(ConfigurableApplicationContext context, int count) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(
                                                context.getBean(JdbcClient.class)
                                                        .sql(
                                                                "SELECT count(*) FROM documents WHERE active_indexing_version IS NOT NULL")
                                                        .query(Integer.class)
                                                        .single())
                                        .isEqualTo(count));
    }
}
