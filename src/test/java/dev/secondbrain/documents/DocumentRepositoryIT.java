package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

@Testcontainers
class DocumentRepositoryIT {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    private static JdbcDocumentRepository repository;

    @BeforeAll
    static void migrateDatabaseAndCreateRepository() {
        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        repository = new JdbcDocumentRepository(JdbcClient.create(dataSource));
    }

    @Test
    void insertsAndReadsMetadataWithoutLosingValues() {
        var createdAt = Instant.parse("2026-01-01T00:00:00.123456Z");
        var metadata =
                new DocumentMetadata(
                        UUID.randomUUID(),
                        "meeting notes.md",
                        DocumentType.MARKDOWN,
                        UUID.randomUUID(),
                        "a".repeat(64),
                        123,
                        1,
                        ExtractionState.QUEUED,
                        createdAt,
                        createdAt.plusSeconds(5));

        repository.insert(metadata);

        assertThat(repository.findById(metadata.documentId())).contains(metadata);
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }
}
