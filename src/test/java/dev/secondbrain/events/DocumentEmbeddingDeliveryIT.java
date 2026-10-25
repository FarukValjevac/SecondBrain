package dev.secondbrain.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.documents.DocumentAcceptanceService;
import dev.secondbrain.documents.DocumentDeletionService;
import dev.secondbrain.documents.DocumentIndexingService;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Real worker boundaries and PostgreSQL; inference is deterministic and never leaves this JVM. */
@Testcontainers
class DocumentEmbeddingDeliveryIT {
    private static final String HANDLER = "documents.embed-passages.v1";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir private Path storage;
    private final MutableClock clock =
            new MutableClock(Instant.parse("2030-01-01T00:00:00.123456Z"));

    @AfterEach
    void clearOnlyIsolatedFixturesAfterContextsClose() {
        JdbcClient.create(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()))
                .sql("TRUNCATE documents, event_keys CASCADE")
                .update();
    }

    @Test
    void completesAllBatchesWithoutActivatingSearchOrRepeatingAlreadyStoredResults()
            throws Exception {
        var provider = new ControlledProvider();
        try (var context = start(provider, true)) {
            var lease = queued(context);
            var dispatcher = context.getBean(EventDispatcher.class);
            dispatcher.dispatch(lease);
            assertComplete(context, lease);
            int calls = provider.calls.get();
            assertThat(calls).isEqualTo(passageCount(context, lease));
            dispatcher.dispatch(lease);
            assertThat(provider.calls.get()).isEqualTo(calls);
            assertComplete(context, lease);
            assertThat(
                            jdbc(context)
                                    .sql(
                                            "SELECT count(*) FROM event_keys WHERE event_type = 'IndexPublished'")
                                    .query(Long.class)
                                    .single())
                    .isZero();
        }
    }

    @Test
    void restartResumesOnlyMissingPassagesAfterALaterBatchFails() throws Exception {
        var failing = new ControlledProvider();
        failing.failAt = 2;
        UUID delivery;
        long expected;
        try (var context = start(failing, true)) {
            var lease = queued(context);
            delivery = lease.deliveryId();
            expected = passageCount(context, lease);
            context.getBean(EventDispatcher.class).dispatch(lease);
            assertThat(vectorCount(context, lease)).isEqualTo(1);
            assertThat(state(context, lease)).isEqualTo("PENDING");
        }
        clock.advance(Duration.ofSeconds(2));
        var resumed = new ControlledProvider();
        try (var context = start(resumed, true)) {
            var lease =
                    context.getBean(JdbcEventStore.class).claim(UUID.randomUUID()).orElseThrow();
            assertThat(lease.deliveryId()).isEqualTo(delivery);
            assertThat(lease.attempt()).isEqualTo(2);
            context.getBean(EventDispatcher.class).dispatch(lease);
            assertComplete(context, lease);
            assertThat(resumed.calls.get()).isEqualTo(expected - 1);
        }
    }

    @Test
    void aResponseAfterLeaseExpiryCannotBeStoredAndCanBeRequestedAgain() throws Exception {
        var provider = new ControlledProvider();
        try (var context = start(provider, true)) {
            var lease = queued(context);
            provider.beforeResponse = () -> clock.advance(Duration.ofMinutes(3));
            context.getBean(EventDispatcher.class).dispatch(lease);
            assertThat(vectorCount(context, lease)).isZero();
            assertThat(state(context, lease)).isEqualTo("RUNNING");
            var store = context.getBean(JdbcEventStore.class);
            assertThat(store.claim(UUID.randomUUID())).isEmpty(); // record expiry, no new owner yet
            clock.advance(Duration.ofSeconds(2));
            provider.beforeResponse = () -> {};
            var retry = store.claim(UUID.randomUUID()).orElseThrow();
            context.getBean(EventDispatcher.class).dispatch(retry);
            assertComplete(context, retry);
            assertThat(provider.calls.get()).isEqualTo(passageCount(context, retry) + 1);
        }
    }

    @ParameterizedTest
    @EnumSource(Stale.class)
    void changesDuringInferencePreventStaleVectorWrites(Stale change) throws Exception {
        var provider = new ControlledProvider();
        try (var context = start(provider, true)) {
            var lease = queued(context);
            provider.beforeResponse =
                    () -> {
                        switch (change) {
                            case REINDEX ->
                                    context.getBean(DocumentIndexingService.class)
                                            .requestNext(
                                                    lease.event().documentId(), UUID.randomUUID());
                            case DELETE ->
                                    context.getBean(DocumentDeletionService.class)
                                            .request(lease.event().documentId(), UUID.randomUUID());
                            case SOURCE ->
                                    jdbc(context)
                                            .sql(
                                                    "UPDATE documents SET processing_version = processing_version + 1 WHERE document_id = :id")
                                            .param("id", lease.event().documentId())
                                            .update();
                        }
                    };
            context.getBean(EventDispatcher.class).dispatch(lease);
            assertThat(state(context, lease)).isEqualTo("SKIPPED");
            assertThat(vectorCount(context, lease)).isZero();
            assertThat(provider.calls.get()).isEqualTo(1);
        }
    }

    @Test
    void failedAcknowledgmentKeepsStagingAndRetryNeedsNoProviderCalls() throws Exception {
        var provider = new ControlledProvider();
        try (var context = start(provider, true)) {
            var lease = queued(context);
            jdbc(context)
                    .sql(
                            """
                    ALTER TABLE event_deliveries ADD CONSTRAINT reject_embedding_ack
                    CHECK (handler_id <> 'documents.embed-passages.v1' OR state <> 'COMPLETED') NOT VALID
                    """)
                    .update();
            try {
                context.getBean(EventDispatcher.class).dispatch(lease);
                assertThat(state(context, lease)).isEqualTo("PENDING");
                assertThat(vectorCount(context, lease)).isEqualTo(passageCount(context, lease));
            } finally {
                jdbc(context)
                        .sql("ALTER TABLE event_deliveries DROP CONSTRAINT reject_embedding_ack")
                        .update();
            }
            int calls = provider.calls.get();
            clock.advance(Duration.ofSeconds(2));
            var retry =
                    context.getBean(JdbcEventStore.class).claim(UUID.randomUUID()).orElseThrow();
            context.getBean(EventDispatcher.class).dispatch(retry);
            assertComplete(context, retry);
            assertThat(provider.calls.get()).isEqualTo(calls);
        }
    }

    @Test
    void enablingSchedulesRetainedFactsOnceWithoutResettingExistingDeliveries() throws Exception {
        var provider = new ControlledProvider();
        UUID eventId;
        try (var context = start(provider, false)) {
            var id = uploadAndPrepare(context);
            assertThat(context.getBean(JdbcEventStore.class).claim(UUID.randomUUID())).isEmpty();
            eventId =
                    jdbc(context)
                            .sql(
                                    "SELECT event_id FROM event_keys WHERE document_id = :id AND event_type = 'PassagesPrepared'")
                            .param("id", id)
                            .query(UUID.class)
                            .single();
            assertThat(provider.calls.get()).isZero();
        }
        try (var context = start(provider, true)) {
            var events = context.getBean(EventPublisher.class);
            var type = new EventEnvelope.Type("PassagesPrepared", 1);
            assertThat(events.scheduleMissing(type, HANDLER, 100)).isEqualTo(1);
            assertThat(events.scheduleMissing(type, HANDLER, 100)).isZero();
            var lease =
                    context.getBean(JdbcEventStore.class).claim(UUID.randomUUID()).orElseThrow();
            assertThat(lease.event().eventId()).isEqualTo(eventId);
            context.getBean(EventDispatcher.class).dispatch(lease);
            assertComplete(context, lease);
            assertThat(events.scheduleMissing(type, HANDLER, 100)).isZero();
        }
    }

    private DeliveryLease queued(ConfigurableApplicationContext context) throws Exception {
        var id = uploadAndPrepare(context);
        var lease = context.getBean(JdbcEventStore.class).claim(UUID.randomUUID()).orElseThrow();
        assertThat(lease.handlerId()).isEqualTo(HANDLER);
        assertThat(lease.event().documentId()).isEqualTo(id);
        assertThat(passageCount(context, lease)).isGreaterThan(2);
        return lease;
    }

    private UUID uploadAndPrepare(ConfigurableApplicationContext context) throws Exception {
        byte[] bytes = "Synthetic note. ".repeat(200).getBytes(StandardCharsets.UTF_8);
        var id =
                context.getBean(DocumentAcceptanceService.class)
                        .accept(
                                "synthetic.txt",
                                bytes.length,
                                new ByteArrayInputStream(bytes),
                                UUID.randomUUID())
                        .documentId();
        var store = context.getBean(JdbcEventStore.class);
        for (var handler :
                List.of(
                        "documents.extract-text.v1",
                        "documents.request-index.v1",
                        "documents.prepare-passages.v1")) {
            var lease = store.claim(UUID.randomUUID()).orElseThrow();
            assertThat(lease.handlerId()).isEqualTo(handler);
            context.getBean(EventDispatcher.class).dispatch(lease);
            assertThat(state(context, lease)).isEqualTo("COMPLETED");
        }
        return id;
    }

    private void assertComplete(ConfigurableApplicationContext context, DeliveryLease lease) {
        assertThat(state(context, lease)).isEqualTo("COMPLETED");
        assertThat(vectorCount(context, lease)).isEqualTo(passageCount(context, lease));
        assertThat(
                        jdbc(context)
                                .sql(
                                        "SELECT count(*) FROM document_embeddings WHERE document_id = :id AND model_id = '@cf/baai/bge-m3' AND dimensions = 1024")
                                .param("id", lease.event().documentId())
                                .query(Long.class)
                                .single())
                .isEqualTo(passageCount(context, lease));
        assertThat(
                        jdbc(context)
                                .sql(
                                        "SELECT outcome FROM event_delivery_attempts WHERE delivery_id = :id AND attempt = :attempt")
                                .param("id", lease.deliveryId())
                                .param("attempt", lease.attempt())
                                .query(String.class)
                                .single())
                .isEqualTo("COMPLETED");
    }

    private static long passageCount(ConfigurableApplicationContext context, DeliveryLease lease) {
        return jdbc(context)
                .sql(
                        "SELECT count(*) FROM document_passages WHERE document_id = :id AND indexing_version = :version")
                .param("id", lease.event().documentId())
                .param("version", lease.event().processingVersion())
                .query(Long.class)
                .single();
    }

    private static long vectorCount(ConfigurableApplicationContext context, DeliveryLease lease) {
        return jdbc(context)
                .sql(
                        "SELECT count(*) FROM document_embeddings WHERE document_id = :id AND indexing_version = :version")
                .param("id", lease.event().documentId())
                .param("version", lease.event().processingVersion())
                .query(Long.class)
                .single();
    }

    private static String state(ConfigurableApplicationContext context, DeliveryLease lease) {
        return jdbc(context)
                .sql("SELECT state FROM event_deliveries WHERE delivery_id = :id")
                .param("id", lease.deliveryId())
                .query(String.class)
                .single();
    }

    private static JdbcClient jdbc(ConfigurableApplicationContext context) {
        return context.getBean(JdbcClient.class);
    }

    private ConfigurableApplicationContext start(ControlledProvider provider, boolean enabled) {
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(
                        context -> {
                            context.getBeanFactory().registerSingleton("embeddingTestClock", clock);
                            context.getBeanFactory()
                                    .registerSingleton("controlledEmbeddingProvider", provider);
                        })
                .run(
                        "--spring.config.import=",
                        "--secondbrain.embeddings.enabled=" + enabled,
                        "--secondbrain.embeddings.batch-size=1",
                        "--secondbrain.events.polling-enabled=false",
                        "--secondbrain.documents.cleanup.enabled=false",
                        "--secondbrain.documents.indexing.reconcile-enabled=false",
                        "--secondbrain.documents.extraction.reconcile-enabled=false",
                        "--secondbrain.documents.deletion.reconcile-enabled=false",
                        "--secondbrain.events.initial-backoff=1s",
                        "--secondbrain.events.max-backoff=1s",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--secondbrain.documents.storage-directory=" + storage);
    }

    enum Stale {
        REINDEX,
        SOURCE,
        DELETE
    }

    private static final class ControlledProvider implements EmbeddingProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private int failAt;
        private Runnable beforeResponse = () -> {};

        @Override
        public EmbeddingModel model() {
            return new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
        }

        @Override
        public List<EmbeddingVector> embed(List<String> inputs) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (calls.incrementAndGet() == failAt)
                throw new RuntimeException("private-test-provider-failure");
            beforeResponse.run();
            return inputs.stream()
                    .map(
                            ignored -> {
                                float[] values = new float[1024];
                                values[0] = 1;
                                return new EmbeddingVector(model(), values);
                            })
                    .toList();
        }
    }
}
