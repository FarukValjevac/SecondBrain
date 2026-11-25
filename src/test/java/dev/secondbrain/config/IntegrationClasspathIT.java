package dev.secondbrain.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.Properties;

/** Runs after packaging, when integration tests must still have their application resources. */
class IntegrationClasspathIT {
    @Test
    void configurationMigrationsAndViewsAreAvailableWithoutSourceDirectoryFallback()
            throws IOException {
        var resources = new PathMatchingResourcePatternResolver();
        var configuration = resources.getResource("classpath:application.properties");
        assertThat(configuration.exists())
                .as("Application configuration on the integration classpath")
                .isTrue();
        var settings = new Properties();
        try (var input = configuration.getInputStream()) {
            settings.load(input);
        }
        assertThat(settings).containsKey("secondbrain.documents.max-upload-size");
        assertThat(resources.getResource("classpath:application-observability.properties").exists())
                .isTrue();
        assertThat(resources.getResources("classpath*:db/migration/V*__*.sql"))
                .as("Flyway migrations on the integration classpath")
                .isNotEmpty();
        for (var path :
                new String[] {
                    "templates/library.html",
                    "templates/document.html",
                    "templates/document-delete.html",
                    "templates/document-cleanup.html",
                    "templates/search.html",
                    "templates/questions.html",
                    "templates/fragments/document-query.html",
                    "templates/fragments/processing.html",
                    "templates/folders.html",
                    "templates/folder-delete.html",
                    "templates/fragments/folders.html",
                    "static/css/app.css",
                    "static/favicon.svg",
                    "static/favicon-32.png",
                    "static/apple-touch-icon.png",
                    "static/fonts/ibm-plex-sans-latin-400-normal.woff2",
                    "static/fonts/ibm-plex-mono-latin-500-normal.woff2",
                    "static/js/app.js",
                    "static/js/chat.js",
                    "static/js/library.js",
                    "static/js/dialogs.js",
                    "static/js/palette.js",
                    "static/js/toasts.js",
                    "templates/fragments/shell.html",
                    "static/js/forms.js"
                }) {
            assertThat(resources.getResource("classpath:" + path).exists())
                    .as("Classpath resource %s", path)
                    .isTrue();
        }
    }
}
