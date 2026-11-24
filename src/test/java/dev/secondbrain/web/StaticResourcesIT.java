package dev.secondbrain.web;

import static dev.secondbrain.TestHttp.client;
import static dev.secondbrain.TestHttp.get;
import static dev.secondbrain.TestHttp.origin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.TestDatabase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** Real application: hashed asset URLs, long caching for styles and fonts, revalidated scripts. */
class StaticResourcesIT {
    @TempDir private Path storage;

    @Test
    void servesHashedAssetsWithTheirCachingPolicy() throws Exception {
        try (var postgres = TestDatabase.create();
                var context =
                        new SpringApplicationBuilder(SecondBrainApplication.class)
                                .web(WebApplicationType.SERVLET)
                                .registerShutdownHook(false)
                                .run(
                                        postgres.arguments(
                                                "--secondbrain.telemetry.otlp-endpoint=",
                                                "--server.address=127.0.0.1",
                                                "--server.port=0",
                                                "--secondbrain.events.polling-enabled=false",
                                                "--secondbrain.documents.storage-directory="
                                                        + storage));
                var client = client()) {
            var origin = origin(context);
            var page = get(client, origin, "/library").body();
            var css = find(page, "/css/app-[0-9a-f]{32}\\.css");
            var script = find(page, "/js/app-[0-9a-f]{32}\\.js");
            assertThat(page).containsPattern("/favicon-[0-9a-f]{32}\\.svg");

            var stylesheet = get(client, origin, css);
            assertThat(stylesheet.statusCode()).as(css).isEqualTo(200);
            assertThat(stylesheet.headers().firstValue("Cache-Control"))
                    .hasValueSatisfying(
                            value -> assertThat(value).contains("max-age=31536000", "immutable"));
            // Fonts referenced by the stylesheet are hashed and cached the same way.
            var font =
                    find(
                            stylesheet.body(),
                            "/fonts/ibm-plex-sans-latin-400-normal-[0-9a-f]{32}\\.woff2");
            assertThat(get(client, origin, font).headers().firstValue("Cache-Control"))
                    .hasValueSatisfying(value -> assertThat(value).contains("max-age=31536000"));

            var js = get(client, origin, script);
            assertThat(js.statusCode()).isEqualTo(200);
            assertThat(js.headers().firstValue("Cache-Control")).hasValue("no-cache");
            // Module imports keep their plain names and are revalidated too.
            assertThat(get(client, origin, "/js/dialogs.js").headers().firstValue("Cache-Control"))
                    .hasValue("no-cache");
        }
    }

    private static String find(String text, String pattern) {
        var matcher = Pattern.compile(pattern).matcher(text);
        assertThat(matcher.find()).as("expected %s", pattern).isTrue();
        return matcher.group();
    }
}
