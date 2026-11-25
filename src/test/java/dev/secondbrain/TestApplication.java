package dev.secondbrain;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts the real application for an integration test: a servlet web server, no JVM shutdown hook
 * (the test closes the context), and the given command-line arguments, usually built with {@link
 * TestDatabase#arguments(String...)}.
 */
public final class TestApplication {
    private TestApplication() {}

    public static ConfigurableApplicationContext run(String... arguments) {
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.SERVLET)
                .registerShutdownHook(false)
                .run(arguments);
    }
}
