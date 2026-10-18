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
        assertThat(resources.getResources("classpath*:db/migration/V*__*.sql"))
                .as("Flyway migrations on the integration classpath")
                .isNotEmpty();
        for (var path :
                new String[] {
                    "templates/library.html",
                    "templates/document.html",
                    "templates/document-delete.html",
                    "static/css/library.css"
                }) {
            assertThat(resources.getResource("classpath:" + path).exists())
                    .as("Classpath resource %s", path)
                    .isTrue();
        }
    }
}
