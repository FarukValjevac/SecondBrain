package dev.secondbrain;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One PostgreSQL/pgvector container per test JVM, and a new empty database inside it for every
 * caller. Each test class keeps the isolation of its own container without paying for a container
 * start each time. Testcontainers removes the container when the JVM exits.
 */
public final class TestDatabase implements AutoCloseable {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static PostgreSQLContainer container;
    private final String name;
    private final String jdbcUrl;

    private TestDatabase(String name, String jdbcUrl) {
        this.name = name;
        this.jdbcUrl = jdbcUrl;
    }

    /** Creates an empty database; Flyway or the test builds its schema as with a new container. */
    public static TestDatabase create() {
        var server = server();
        var name = "test_" + SEQUENCE.incrementAndGet();
        execute(server.getJdbcUrl(), "CREATE DATABASE " + name);
        // This disposable local fixture has no TLS configuration or certificates.
        return new TestDatabase(
                name,
                "jdbc:postgresql://"
                        + server.getHost()
                        + ":"
                        + server.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                        + "/"
                        + name
                        + "?loggerLevel=OFF&sslmode=disable");
    }

    private static synchronized PostgreSQLContainer server() {
        if (container == null) {
            container =
                    new PostgreSQLContainer(
                            DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                                    .asCompatibleSubstituteFor("postgres"));
            container.start();
        }
        return container;
    }

    /**
     * The number of Flyway migrations shipped with the application, read from the migration files
     * so a new migration needs no test edits. Versions are consecutive, so this is also the latest
     * version.
     */
    public static int migrationCount() {
        try {
            return new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/V*__*.sql")
                    .length;
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException("Migrations could not be listed", unreadable);
        }
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public String getUsername() {
        return server().getUsername();
    }

    public String getPassword() {
        return server().getPassword();
    }

    /**
     * Application arguments that point SecondBrain at this database and ignore the developer's
     * private .env file, followed by the test's own settings.
     */
    public String[] arguments(String... settings) {
        var all = new String[settings.length + 4];
        all[0] = "--spring.config.import=";
        all[1] = "--spring.datasource.url=" + getJdbcUrl();
        all[2] = "--spring.datasource.username=" + getUsername();
        all[3] = "--spring.datasource.password=" + getPassword();
        System.arraycopy(settings, 0, all, 4, settings.length);
        return all;
    }

    /** Drops the database; callers must close their application contexts first. */
    @Override
    public void close() {
        execute(server().getJdbcUrl(), "DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
    }

    // Names come only from the sequence above, never from test or document input.
    private static void execute(String url, String statement) {
        var server = server();
        try (var connection =
                        DriverManager.getConnection(
                                url, server.getUsername(), server.getPassword());
                var sql = connection.createStatement()) {
            sql.execute(statement);
        } catch (SQLException failure) {
            throw new IllegalStateException("Test database setup failed", failure);
        }
    }
}
