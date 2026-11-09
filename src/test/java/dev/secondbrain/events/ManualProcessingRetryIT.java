package dev.secondbrain.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.documents.DocumentAcceptanceService;
import dev.secondbrain.documents.DocumentDeletionService;
import dev.secondbrain.documents.DocumentIndexingService;
import dev.secondbrain.documents.DocumentProcessingService;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Isolated real PostgreSQL/handlers, with explicit claims and no live inference or wall-clock
 * sleeps.
 */
@Testcontainers
class ManualProcessingRetryIT {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir private Path storage;
    private final MutableClock clock =
            new MutableClock(Instant.parse("2030-01-01T00:00:00.123456Z"));

    @AfterEach
    void cleanupAfterAllWorkersClose() {
        JdbcClient.create(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()))
                .sql("TRUNCATE documents, event_keys CASCADE")
                .update();
    }

    @Test
    void resumesSavedBatchesAcrossRestartWithSameIdentityAndMonotonicHistory() throws Exception {
        UUID id;
        UUID delivery;
        String envelope;
        List<UUID> passages;
        var failing = new Provider();
        failing.failAfter = 1;
        try (var context = start(failing)) {
            var lease = queuedEmbedding(context);
            id = lease.event().documentId();
            delivery = lease.deliveryId();
            envelope = envelope(context, lease.event().eventId());
            passages = passageIds(context, id);
            failCycle(context, lease);
            var processing = context.getBean(DocumentProcessingService.class);
            var failed = processing.read(Set.of(id)).get(id);
            assertThat(failed.state()).isEqualTo(DocumentProcessingService.State.FAILED);
            assertThat(failed.vectors()).isEqualTo(1);
            assertThat(processing.retry(id, delivery, 0, UUID.randomUUID())).isTrue();
            assertThat(processing.retry(id, delivery, 0, UUID.randomUUID())).isFalse();
            // Delay this isolated fixture so startup cannot claim it before the test stops the
            // poller.
            sql(context)
                    .sql("UPDATE event_deliveries SET eligible_at = :at WHERE delivery_id = :id")
                    .param("at", java.sql.Timestamp.from(clock.instant().plusSeconds(30)))
                    .param("id", delivery)
                    .update();
            assertThat(
                            sql(context)
                                    .sql(
                                            "SELECT attempts FROM event_deliveries WHERE delivery_id = :id")
                                    .param("id", delivery)
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(2);
        }
        var healthy = new Provider();
        try (var context = start(healthy)) {
            clock.advance(Duration.ofSeconds(31));
            var lease = claim(context);
            assertThat(lease.deliveryId()).isEqualTo(delivery);
            assertThat(lease.attempt()).isEqualTo(3);
            assertThat(envelope(context, lease.event().eventId())).isEqualTo(envelope);
            context.getBean(EventDispatcher.class).dispatch(lease);
            var ready = context.getBean(DocumentProcessingService.class).read(Set.of(id)).get(id);
            assertThat(ready.state()).isEqualTo(DocumentProcessingService.State.READY);
            assertThat(ready.vectors()).isEqualTo(ready.passages());
            assertThat(ready.retry()).isEmpty();
            assertThat(passageIds(context, id)).containsExactlyElementsOf(passages);
            assertThat(healthy.calls).isEqualTo(passages.size() - 1);
            assertThat(
                            sql(context)
                                    .sql(
                                            "SELECT outcome FROM event_delivery_attempts WHERE delivery_id = :id ORDER BY attempt")
                                    .param("id", delivery)
                                    .query(String.class)
                                    .list())
                    .containsExactly("FAILED", "FAILED", "COMPLETED");
            assertThat(
                            sql(context)
                                    .sql(
                                            "SELECT count(*) FROM event_keys WHERE document_id = :id AND event_type = 'IndexPublished'")
                                    .param("id", id)
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(1);
            assertThat(
                            context.getBean(DocumentProcessingService.class)
                                    .retry(id, delivery, 1, UUID.randomUUID()))
                    .isFalse();
        }
    }

    @Test
    void boundsEachCycleAndRejectsOldFormsEvenAfterTheNextCycleFails() throws Exception {
        var provider = new Provider();
        provider.failAfter = 0;
        try (var context = start(provider)) {
            var lease = queuedEmbedding(context);
            failCycle(context, lease);
            var service = context.getBean(DocumentProcessingService.class);
            var id = lease.event().documentId();
            var delivery = lease.deliveryId();
            assertThat(service.retry(id, delivery, 0, UUID.randomUUID())).isTrue();
            assertThat(
                            context.getBean(JdbcEventStore.class)
                                    .complete(
                                            lease,
                                            () -> {
                                                throw new AssertionError(
                                                        "A stale lease applied effects after manual retry");
                                            }))
                    .isFalse();
            failCycle(context, claim(context));
            assertThat(service.retry(id, delivery, 0, UUID.randomUUID())).isFalse();
            assertThat(provider.calls).isEqualTo(4);
            assertThat(
                            sql(context)
                                    .sql(
                                            "SELECT attempts FROM event_deliveries WHERE delivery_id = :id")
                                    .param("id", delivery)
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(4);
            assertThat(
                            sql(context)
                                    .sql(
                                            "SELECT cycle_attempts FROM event_deliveries WHERE delivery_id = :id")
                                    .param("id", delivery)
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(2);
            assertThat(
                            sql(context)
                                    .sql(
                                            "SELECT count(*) FROM event_delivery_retries WHERE delivery_id = :id")
                                    .param("id", delivery)
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(1);
            assertThat(service.retry(id, delivery, 1, UUID.randomUUID())).isTrue();
        }
    }

    @Test
    void concurrentRequestsStartExactlyOneNewCycle() throws Exception {
        var provider = new Provider();
        provider.failAfter = 0;
        try (var context = start(provider);
                var executor = Executors.newFixedThreadPool(2)) {
            var lease = queuedEmbedding(context);
            failCycle(context, lease);
            var gate = new CyclicBarrier(2);
            Callable<Boolean> retry =
                    () -> {
                        gate.await(5, TimeUnit.SECONDS);
                        return context.getBean(DocumentProcessingService.class)
                                .retry(
                                        lease.event().documentId(),
                                        lease.deliveryId(),
                                        0,
                                        UUID.randomUUID());
                    };
            var first = executor.submit(retry);
            var second = executor.submit(retry);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(
                            sql(context)
                                    .sql("SELECT count(*) FROM event_delivery_retries")
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionAndNewIndexVersionsFenceRetry(boolean delete) throws Exception {
        var provider = new Provider();
        provider.failAfter = 0;
        try (var context = start(provider)) {
            var lease = queuedEmbedding(context);
            failCycle(context, lease);
            var id = lease.event().documentId();
            if (delete)
                context.getBean(DocumentDeletionService.class).request(id, UUID.randomUUID());
            else context.getBean(DocumentIndexingService.class).requestNext(id, UUID.randomUUID());
            var service = context.getBean(DocumentProcessingService.class);
            assertThat(service.retry(id, lease.deliveryId(), 0, UUID.randomUUID())).isFalse();
            if (delete) {
                assertThat(service.read(Set.of(id))).isEmpty();
                assertThat(service.cleanup().pending()).isEqualTo(1);
            }
            assertThat(state(context, lease.deliveryId())).isEqualTo("FAILED");
        }
    }

    @Test
    void retryAndFailureClearRollBackTogetherWhenAuditWriteFails() throws Exception {
        try (var context = start(new Provider())) {
            var bytes = new byte[] {(byte) 0xC3, 0x28};
            context.getBean(DocumentAcceptanceService.class)
                    .accept(
                            "invalid.txt",
                            bytes.length,
                            new ByteArrayInputStream(bytes),
                            UUID.randomUUID());
            var lease = claim(context);
            context.getBean(EventDispatcher.class).dispatch(lease);
            assertThat(state(context, lease.deliveryId())).isEqualTo("FAILED");
            sql(context)
                    .sql(
                            "ALTER TABLE event_delivery_retries ADD CONSTRAINT reject_retry CHECK(retry_cycle < 0)")
                    .update();
            try {
                assertThatThrownBy(
                                () ->
                                        context.getBean(DocumentProcessingService.class)
                                                .retry(
                                                        lease.event().documentId(),
                                                        lease.deliveryId(),
                                                        0,
                                                        UUID.randomUUID()))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(state(context, lease.deliveryId())).isEqualTo("FAILED");
                assertThat(
                                sql(context)
                                        .sql(
                                                "SELECT extraction_failure FROM documents WHERE document_id = :id")
                                        .param("id", lease.event().documentId())
                                        .query(String.class)
                                        .single())
                        .isEqualTo("INVALID_ENCODING");
                assertThat(
                                sql(context)
                                        .sql(
                                                "SELECT retry_cycle FROM event_deliveries WHERE delivery_id = :id")
                                        .param("id", lease.deliveryId())
                                        .query(Integer.class)
                                        .single())
                        .isZero();
            } finally {
                sql(context)
                        .sql("ALTER TABLE event_delivery_retries DROP CONSTRAINT reject_retry")
                        .update();
            }
        }
    }

    private void failCycle(ConfigurableApplicationContext context, DeliveryLease first) {
        var dispatcher = context.getBean(EventDispatcher.class);
        dispatcher.dispatch(first);
        clock.advance(Duration.ofSeconds(2));
        dispatcher.dispatch(claim(context));
        assertThat(state(context, first.deliveryId())).isEqualTo("FAILED");
    }

    private DeliveryLease queuedEmbedding(ConfigurableApplicationContext context) throws Exception {
        var bytes = ("Long study note ALPHA. ".repeat(180)).getBytes(StandardCharsets.UTF_8);
        context.getBean(DocumentAcceptanceService.class)
                .accept(
                        "notes.md",
                        bytes.length,
                        new ByteArrayInputStream(bytes),
                        UUID.randomUUID());
        var dispatcher = context.getBean(EventDispatcher.class);
        for (int i = 0; i < 3; i++) dispatcher.dispatch(claim(context));
        var lease = claim(context);
        assertThat(lease.handlerId()).isEqualTo("documents.embed-passages.v1");
        return lease;
    }

    private DeliveryLease claim(ConfigurableApplicationContext context) {
        return context.getBean(JdbcEventStore.class).claim(UUID.randomUUID()).orElseThrow();
    }

    private String state(ConfigurableApplicationContext context, UUID id) {
        return sql(context)
                .sql("SELECT state FROM event_deliveries WHERE delivery_id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private String envelope(ConfigurableApplicationContext context, UUID id) {
        return sql(context)
                .sql("SELECT row_to_json(e)::text FROM domain_events e WHERE event_id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private List<UUID> passageIds(ConfigurableApplicationContext context, UUID id) {
        return sql(context)
                .sql(
                        "SELECT passage_id FROM document_passages WHERE document_id = :id ORDER BY sequence_number")
                .param("id", id)
                .query(UUID.class)
                .list();
    }

    private static JdbcClient sql(ConfigurableApplicationContext context) {
        return context.getBean(JdbcClient.class);
    }

    private ConfigurableApplicationContext start(Provider provider) {
        var context =
                new SpringApplicationBuilder(SecondBrainApplication.class)
                        .web(WebApplicationType.NONE)
                        .registerShutdownHook(false)
                        .initializers(
                                c -> {
                                    c.getBeanFactory().registerSingleton("retryTestClock", clock);
                                    c.getBeanFactory()
                                            .registerSingleton("retryTestProvider", provider);
                                })
                        .run(
                                "--spring.config.import=",
                                "--secondbrain.telemetry.otlp-endpoint=",
                                "--secondbrain.embeddings.enabled=true",
                                "--secondbrain.embeddings.batch-size=1",
                                "--secondbrain.events.polling-enabled=true",
                                "--secondbrain.events.poll-interval=30s",
                                "--secondbrain.events.lease-duration=90s",
                                "--secondbrain.events.execution-timeout=2m",
                                "--secondbrain.events.max-attempts=2",
                                "--secondbrain.events.initial-backoff=1s",
                                "--secondbrain.events.max-backoff=1s",
                                "--secondbrain.documents.cleanup.enabled=false",
                                "--secondbrain.documents.indexing.reconcile-enabled=false",
                                "--secondbrain.documents.extraction.reconcile-enabled=false",
                                "--secondbrain.documents.deletion.reconcile-enabled=false",
                                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                                "--spring.datasource.username=" + POSTGRES.getUsername(),
                                "--spring.datasource.password=" + POSTGRES.getPassword(),
                                "--secondbrain.documents.storage-directory=" + storage);
        // Stop before creating fixtures. The configured capability remains enabled for the API.
        context.getBean(DurableEventWorker.class).stop();
        return context;
    }

    private static final class Provider implements EmbeddingProvider {
        private int calls;
        private int failAfter = Integer.MAX_VALUE;

        @Override
        public EmbeddingModel model() {
            return new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
        }

        @Override
        public List<EmbeddingVector> embed(List<String> inputs) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (++calls > failAfter) throw new RuntimeException("private-provider-sentinel");
            return inputs.stream()
                    .map(
                            ignored -> {
                                var vector = new float[1024];
                                vector[0] = 1;
                                return new EmbeddingVector(model(), vector);
                            })
                    .toList();
        }
    }
}
