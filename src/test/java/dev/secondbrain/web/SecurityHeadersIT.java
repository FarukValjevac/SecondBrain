package dev.secondbrain.web;

import static dev.secondbrain.TestHttp.client;
import static dev.secondbrain.TestHttp.get;
import static dev.secondbrain.TestHttp.origin;
import static dev.secondbrain.TestHttp.post;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.TestDatabase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

/** Real application: security headers on every response, a strict cookie, styled error pages. */
class SecurityHeadersIT {
    @TempDir private Path storage;

    @Test
    void protectsPagesAndExplainsErrorsInsideTheApp() throws Exception {
        try (var postgres = TestDatabase.create();
                var context =
                        dev.secondbrain.TestApplication.run(
                                postgres.arguments(
                                        "--secondbrain.telemetry.otlp-endpoint=",
                                        "--server.address=127.0.0.1",
                                        "--server.port=0",
                                        "--secondbrain.events.polling-enabled=false",
                                        "--secondbrain.documents.storage-directory=" + storage));
                var client = client()) {
            var origin = origin(context);
            var page = get(client, origin, "/library");
            assertThat(page.headers().firstValue("Content-Security-Policy"))
                    .hasValueSatisfying(
                            policy ->
                                    assertThat(policy)
                                            .contains(
                                                    "default-src 'self'",
                                                    "script-src 'self'",
                                                    "frame-ancestors 'self'",
                                                    "object-src 'none'"));
            assertThat(page.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
            assertThat(page.headers().firstValue("X-Frame-Options")).hasValue("SAMEORIGIN");
            // Pages carry no inline scripts or styles that the policy would block.
            assertThat(page.body()).doesNotContain("<script>", " style=\"", "onclick=");

            // Unknown pages and documents get the styled page with a way back.
            for (var path : new String[] {"/no-such-page", "/documents/" + UUID.randomUUID()}) {
                // As a browser asks: Spring answers API clients without Accept with JSON.
                var missing =
                        client.send(
                                java.net.http.HttpRequest.newBuilder(origin.resolve(path))
                                        .header("Accept", "text/html,application/xhtml+xml")
                                        .GET()
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(missing.statusCode()).isEqualTo(404);
                assertThat(missing.headers().firstValue("Content-Type"))
                        .hasValueSatisfying(type -> assertThat(type).startsWith("text/html"));
                assertThat(missing.body())
                        .contains("Page not found", "Open the library", "class=\"sidebar\"")
                        .doesNotContain("Whitelabel", "Exception");
            }

            // A one-time message uses the session; its cookie is never sent by other sites.
            var flash = post(client, origin, "/folders", "name=&parent=&color=green");
            assertThat(flash.headers().allValues("Set-Cookie"))
                    .anySatisfy(
                            cookie -> assertThat(cookie).contains("SameSite=Strict", "HttpOnly"));
        }
    }
}
