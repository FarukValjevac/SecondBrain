package dev.secondbrain.config;

import dev.secondbrain.SecondBrainApplication;

import org.assertj.core.api.Assertions;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class DatabaseMigrationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @Test
    void migratesAnEmptyDatabaseAndPreservesDataOnApplicationRestart() {
        MigrationInfo firstMigration;
        try (var context = startApplication()) {
            var jdbc = context.getBean(JdbcClient.class);
            var flyway = context.getBean(Flyway.class);

            Assertions.assertThat(flyway.info().applied()).hasSize(2);
            firstMigration = flyway.info().current();
            Assertions.assertThat(firstMigration.getVersion().getVersion()).isEqualTo("2");
            Assertions.assertThat(
                            jdbc.sql("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
                                    .query(String.class)
                                    .single())
                    .isEqualTo("0.8.7");
            Assertions.assertThat(
                            jdbc.sql("SELECT '[1,2,3]'::vector <-> '[1,2,4]'::vector")
                                    .query(Double.class)
                                    .single())
                    .isEqualTo(1.0);

            jdbc.sql("CREATE TABLE restart_probe (id integer PRIMARY KEY, content text NOT NULL)")
                    .update();
            jdbc.sql("INSERT INTO restart_probe (id, content) VALUES (1, :content)")
                    .param("content", "survives restart")
                    .update();
        }

        try (var context = startApplication()) {
            var jdbc = context.getBean(JdbcClient.class);
            var flyway = context.getBean(Flyway.class);

            Assertions.assertThat(flyway.info().applied()).hasSize(2);
            Assertions.assertThat(flyway.info().current().getInstalledOn())
                    .isEqualTo(firstMigration.getInstalledOn());
            Assertions.assertThat(
                            jdbc.sql("SELECT content FROM restart_probe WHERE id = 1")
                                    .query(String.class)
                                    .single())
                    .isEqualTo("survives restart");
            Assertions.assertThat(
                            jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success")
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(2);
        }
    }

    private static ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.config.import=",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword());
    }
}
