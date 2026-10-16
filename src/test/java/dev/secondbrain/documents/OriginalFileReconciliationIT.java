package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.events.EventPublisher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Testcontainers
class OriginalFileReconciliationIT {
    private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofHours(2));
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final byte[] CONTENT = "Private test note".getBytes(StandardCharsets.UTF_8);
    private static final OriginalCleanupProperties SETTINGS =
            new OriginalCleanupProperties(Duration.ofHours(1), Duration.ofMinutes(1), 100);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir private Path directory;

    @Test
    void restartRemovesOldOrphansAndPartialFilesButRetainsCommittedAndRecentOriginals()
            throws Exception {
        UUID documentId;
        UUID storageId;
        try (var context = startApplication()) {
            var accepted =
                    context.getBean(DocumentAcceptanceService.class)
                            .accept(
                                    "note.md",
                                    CONTENT.length,
                                    new ByteArrayInputStream(CONTENT),
                                    UUID.randomUUID());
            documentId = accepted.documentId();
            storageId =
                    context.getBean(JdbcDocumentRepository.class)
                            .findById(documentId)
                            .orElseThrow()
                            .storageId();
        }
        var owned = directory.resolve(storageId + ".original");
        Files.setLastModifiedTime(owned, FileTime.from(OLD));
        var abandoned = fixture(CONTENT, OLD);
        var partial = fixture(new byte[0], OLD);
        var recent = fixture(CONTENT, NOW);

        try (var restarted = startApplication();
                var cleanup = reconciler(restarted)) {
            assertThat(cleanup.reconcileBatch().deleted()).isEqualTo(2);
            assertThat(abandoned).doesNotExist();
            assertThat(partial).doesNotExist();
            assertThat(recent).exists();
            assertThat(Files.readAllBytes(owned)).containsExactly(CONTENT);
            var repository = restarted.getBean(JdbcDocumentRepository.class);
            assertThat(repository.ownsStorageId(storageId)).isTrue();
            assertThat(repository.findById(documentId)).isPresent();
            assertThat(
                            restarted
                                    .getBean(JdbcClient.class)
                                    .sql(
                                            "SELECT count(*) FROM event_keys WHERE document_id = :id AND event_type = 'DocumentAccepted'")
                                    .param("id", documentId)
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(1);
            assertThat(cleanup.reconcileBatch().deleted()).isZero();
        }
    }

    @Test
    void concurrentCleanupCannotRemoveAnActiveWriteOrAnUncommittedAcceptance() throws Exception {
        var writing = new CountDownLatch(1);
        var allowWrite = new CountDownLatch(1);
        var beforeCommit = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        try (var context = startApplication();
                var cleanup = reconciler(context);
                var executor = Executors.newSingleThreadExecutor()) {
            var template =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var heldTransactions =
                    new TransactionOperations() {
                        @Override
                        public <T> T execute(TransactionCallback<T> action) {
                            return template.execute(
                                    status -> {
                                        var result = action.doInTransaction(status);
                                        beforeCommit.countDown();
                                        await(allowCommit);
                                        return result;
                                    });
                        }
                    };
            var documents = context.getBean(JdbcDocumentRepository.class);
            var service =
                    new DocumentAcceptanceService(
                            context.getBean(DocumentUploadPolicy.class),
                            context.getBean(OriginalFileStore.class),
                            documents,
                            context.getBean(EventPublisher.class),
                            heldTransactions,
                            CLOCK);
            var input =
                    new ByteArrayInputStream(CONTENT) {
                        @Override
                        public synchronized int read(byte[] buffer, int offset, int length) {
                            writing.countDown();
                            await(allowWrite);
                            return super.read(buffer, offset, length);
                        }
                    };
            var accepting =
                    executor.submit(
                            () ->
                                    service.accept(
                                            "note.txt", CONTENT.length, input, UUID.randomUUID()));
            try {
                assertThat(writing.await(10, TimeUnit.SECONDS)).isTrue();
                Path active;
                try (var paths = Files.list(directory)) {
                    active = paths.findFirst().orElseThrow();
                }
                var storageId = UUID.fromString(active.getFileName().toString().substring(0, 36));
                Files.setLastModifiedTime(active, FileTime.from(OLD));
                assertThat(cleanup.reconcileBatch().busy()).isEqualTo(1);
                assertThat(active).exists();
                assertThat(documents.ownsStorageId(storageId)).isFalse();

                allowWrite.countDown();
                assertThat(beforeCommit.await(10, TimeUnit.SECONDS)).isTrue();
                Files.setLastModifiedTime(active, FileTime.from(OLD));
                assertThat(cleanup.reconcileBatch().busy()).isEqualTo(1);
                assertThat(active).exists();
                // Query from a different connection: the metadata is still uncommitted.
                assertThat(documents.ownsStorageId(storageId)).isFalse();

                allowCommit.countDown();
                var accepted = accepting.get(10, TimeUnit.SECONDS);
                assertThat(accepted.state()).isEqualTo(ExtractionState.QUEUED);
                assertThat(documents.ownsStorageId(storageId)).isTrue();
                assertThat(cleanup.reconcileBatch().deleted()).isZero();
                assertThat(Files.readAllBytes(active)).containsExactly(CONTENT);
            } finally {
                allowWrite.countDown();
                allowCommit.countDown();
            }
        }
    }

    private OriginalFileReconciler reconciler(ConfigurableApplicationContext context) {
        return new OriginalFileReconciler(
                context.getBean(OriginalFileStore.class),
                context.getBean(JdbcDocumentRepository.class),
                SETTINGS,
                CLOCK);
    }

    private Path fixture(byte[] content, Instant modifiedAt) throws Exception {
        var path = Files.write(directory.resolve(UUID.randomUUID() + ".original"), content);
        Files.setLastModifiedTime(path, FileTime.from(modifiedAt));
        return path;
    }

    private ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.config.import=",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--secondbrain.events.polling-enabled=false",
                        "--secondbrain.documents.cleanup.enabled=false",
                        "--secondbrain.documents.storage-directory=" + directory);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out coordinating test upload");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test upload interrupted", interrupted);
        }
    }
}
