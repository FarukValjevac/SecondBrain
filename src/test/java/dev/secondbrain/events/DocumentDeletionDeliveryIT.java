package dev.secondbrain.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.documents.DocumentAcceptanceService;
import dev.secondbrain.documents.DocumentDeletionService;
import dev.secondbrain.documents.DocumentIndexingService;
import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.ExtractionState;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises real document handlers through the package-private delivery/lease boundary. */
@Testcontainers
class DocumentDeletionDeliveryIT {
    private static final String DELETE_HANDLER = "documents.delete.v1";
    private static final byte[] CONTENT = "Study notes\nCafé 📚\n".getBytes(StandardCharsets.UTF_8);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:0.8.7-pg18-bookworm");

    @TempDir private Path storage;
    private final MutableClock clock =
            new MutableClock(Instant.parse("2030-01-01T00:00:00.123456Z"));
    private final AtomicInteger embeddingCalls = new AtomicInteger();
    private final EmbeddingProvider embeddings =
            new EmbeddingProvider() {
                @Override
                public EmbeddingModel model() {
                    return new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
                }

                @Override
                public List<EmbeddingVector> embed(List<String> inputs) {
                    assertThat(
                                    org.springframework.transaction.support
                                            .TransactionSynchronizationManager
                                            .isActualTransactionActive())
                            .isFalse();
                    embeddingCalls.incrementAndGet();
                    return inputs.stream()
                            .map(
                                    ignored -> {
                                        float[] values = new float[1024];
                                        values[0] = 1;
                                        return new EmbeddingVector(model(), values);
                                    })
                            .toList();
                }
            };

    @AfterEach
    void clearOnlyTheIsolatedDatabase() {
        JdbcClient.create(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()))
                .sql("TRUNCATE documents, event_keys CASCADE")
                .update();
    }

    @Test
    void competingContextsClaimAndCompleteOnlyOneCleanupDelivery() throws Exception {
        try (var first = start();
                var second = start();
                var workers = Executors.newFixedThreadPool(2)) {
            var fixture = upload(first);
            extract(first, fixture);
            requestDeletion(first, fixture);
            var ready = new CyclicBarrier(2);
            var claimed = new CyclicBarrier(2);
            var results =
                    workers.<Boolean>invokeAll(
                            List.of(
                                    () -> claimAndDispatch(first, ready, claimed),
                                    () -> claimAndDispatch(second, ready, claimed)),
                            20,
                            TimeUnit.SECONDS);

            assertThat(
                            List.of(
                                    results.get(0).get(1, TimeUnit.SECONDS),
                                    results.get(1).get(1, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertCleaned(first, fixture);
            assertThat(attempts(first, fixture)).containsExactly("COMPLETED");
            assertThat(first.getBean(JdbcEventStore.class).claim(UUID.randomUUID())).isEmpty();
            assertThat(second.getBean(JdbcEventStore.class).claim(UUID.randomUUID())).isEmpty();
        }
    }

    @Test
    void expiryDuringFinalizationRollsBackCleanupAndANewLeaseRecoversAfterRestart()
            throws Exception {
        Fixture fixture;
        DeliveryLease stale;
        try (var context = start()) {
            fixture = upload(context);
            extract(context, fixture);
            requestDeletion(context, fixture);
            var store = context.getBean(JdbcEventStore.class);
            stale = store.claim(UUID.randomUUID()).orElseThrow();
            var prepared =
                    context.getBean(EventRegistry.class)
                            .handler(stale.handlerId())
                            .orElseThrow()
                            .prepare(stale.event());
            assertThat(original(fixture)).doesNotExist();

            assertThat(
                            store.complete(
                                    stale,
                                    () -> {
                                        assertThat(prepared.apply())
                                                .isEqualTo(EventHandler.Outcome.COMPLETED);
                                        clock.advance(
                                                context.getBean(EventDeliveryProperties.class)
                                                        .leaseDuration()
                                                        .plusSeconds(1));
                                        return EventHandler.Outcome.COMPLETED;
                                    }))
                    .isFalse();
            // Filesystem deletion cannot roll back; metadata, text, old events, and the lease can.
            assertThat(count(context, "documents", fixture.id())).isEqualTo(1);
            assertThat(count(context, "document_extractions", fixture.id())).isEqualTo(1);
            assertThat(count(context, "document_text_lines", fixture.id())).isGreaterThan(0);
            assertThat(eventCount(context, fixture, "TextExtracted")).isEqualTo(1);
            assertThat(deliveryState(context, fixture)).isEqualTo("RUNNING");
            assertHidden(context, fixture);
        }
        try (var restarted = start()) {
            var store = restarted.getBean(JdbcEventStore.class);
            assertThat(store.claim(UUID.randomUUID())).isEmpty(); // Reap the expired lease.
            assertThat(attempts(restarted, fixture)).containsExactly("EXPIRED");
            clock.advance(
                    restarted.getBean(EventDeliveryProperties.class).maxBackoff().plusSeconds(1));
            var recovered = store.claim(UUID.randomUUID()).orElseThrow();
            assertThat(recovered.deliveryId()).isEqualTo(stale.deliveryId());
            assertThat(recovered.token()).isNotEqualTo(stale.token());
            assertThat(recovered.attempt()).isEqualTo(2);
            var staleEffectRan = new AtomicBoolean();
            assertThat(
                            store.complete(
                                    stale,
                                    () -> {
                                        staleEffectRan.set(true);
                                        return EventHandler.Outcome.COMPLETED;
                                    }))
                    .isFalse();
            assertThat(staleEffectRan).isFalse();

            restarted.getBean(EventDispatcher.class).dispatch(recovered);
            assertCleaned(restarted, fixture);
            assertThat(attempts(restarted, fixture)).containsExactly("EXPIRED", "COMPLETED");
        }
    }

    @Test
    void physicallyCompletedDeletionRejectsAPreparedExtractionAndItsOldLease() throws Exception {
        try (var extracting = start();
                var deleting = start()) {
            var fixture = upload(extracting);
            var extractionLease =
                    extracting.getBean(JdbcEventStore.class).claim(UUID.randomUUID()).orElseThrow();
            var prepared =
                    extracting
                            .getBean(EventRegistry.class)
                            .handler(extractionLease.handlerId())
                            .orElseThrow()
                            .prepare(extractionLease.event());

            requestDeletion(deleting, fixture);
            var cleanup =
                    deleting.getBean(JdbcEventStore.class).claim(UUID.randomUUID()).orElseThrow();
            assertThat(cleanup.handlerId()).isEqualTo(DELETE_HANDLER);
            deleting.getBean(EventDispatcher.class).dispatch(cleanup);
            assertCleaned(deleting, fixture);

            var staleEffectRan = new AtomicBoolean();
            assertThat(
                            extracting
                                    .getBean(JdbcEventStore.class)
                                    .complete(
                                            extractionLease,
                                            () -> {
                                                staleEffectRan.set(true);
                                                return prepared.apply();
                                            }))
                    .isFalse();
            assertThat(staleEffectRan).isFalse();
            // Even a caller bypassing the lease cannot publish text for the removed document.
            var transaction =
                    new TransactionTemplate(extracting.getBean(PlatformTransactionManager.class));
            assertThat(transaction.<EventHandler.Outcome>execute(status -> prepared.apply()))
                    .isEqualTo(EventHandler.Outcome.SKIPPED);
            assertThat(eventCount(extracting, fixture, "TextExtracted")).isZero();
            assertCleaned(extracting, fixture);
        }
    }

    @ParameterizedTest
    @EnumSource(LateStage.class)
    void deletesPublishedAndStagedGenerationsAndFencesLateWork(LateStage stage) throws Exception {
        try (var context = start(true)) {
            var fixture = published(context);
            var late = prepareLateWork(context, fixture, stage);
            var before = indexCounts(context, fixture);
            requestDeletion(context, fixture);
            assertThat(original(fixture)).exists();
            var transaction = transaction(context);
            assertThat(transaction.<EventHandler.Outcome>execute(status -> late.effect().apply()))
                    .isEqualTo(EventHandler.Outcome.SKIPPED);
            assertThat(indexCounts(context, fixture)).isEqualTo(before);
            var fences = keys(context, fixture);
            var cleanup = claimCleanup(context);
            context.getBean(EventDispatcher.class).dispatch(cleanup);
            assertCleaned(context, fixture);
            assertThat(keys(context, fixture)).isEqualTo(fences);

            var store = context.getBean(JdbcEventStore.class);
            var ran = new AtomicBoolean();
            assertThat(
                            store.complete(
                                    late.lease(),
                                    () -> {
                                        ran.set(true);
                                        return late.effect().apply();
                                    }))
                    .isFalse();
            assertThat(store.checkpoint(late.lease(), late.effect())).isEmpty();
            assertThat(ran).isFalse();
            assertThat(transaction.<EventHandler.Outcome>execute(status -> late.effect().apply()))
                    .isEqualTo(EventHandler.Outcome.SKIPPED);
            int calls = embeddingCalls.get();
            assertThat(
                            transaction.<UUID>execute(
                                    status ->
                                            context.getBean(EventPublisher.class)
                                                    .publish(late.lease().event())))
                    .isEqualTo(late.lease().event().eventId());
            assertThat(
                            context.getBean(EventPublisher.class)
                                    .scheduleMissing(
                                            new EventEnvelope.Type("PassagesPrepared", 1),
                                            "documents.embed-passages.v1",
                                            100))
                    .isZero();
            assertThat(
                            context.getBean(EventPublisher.class)
                                    .scheduleMissing(
                                            new EventEnvelope.Type("IndexPublicationRequested", 1),
                                            "documents.publish-index.v1",
                                            100))
                    .isZero();
            assertThat(store.claim(UUID.randomUUID())).isEmpty();
            assertThat(
                            context.getBean(EventRegistry.class)
                                    .handler(DELETE_HANDLER)
                                    .orElseThrow()
                                    .prepare(cleanup.event())
                                    .apply())
                    .isEqualTo(EventHandler.Outcome.SKIPPED);
            assertThat(embeddingCalls.get()).isEqualTo(calls);
            assertThat(keys(context, fixture)).isEqualTo(fences);
            assertCleaned(context, fixture);
        }
    }

    @Test
    void publishedIndexCleanupRollsBackOnExpiryAndRecoversAfterRestart() throws Exception {
        Fixture fixture;
        LateWork late;
        DeliveryLease expired;
        List<Long> before;
        List<UUID> fences;
        try (var context = start(true)) {
            fixture = published(context);
            late = prepareLateWork(context, fixture, LateStage.PUBLICATION);
            before = indexCounts(context, fixture);
            requestDeletion(context, fixture);
            fences = keys(context, fixture);
            expired = claimCleanup(context);
            var prepared =
                    context.getBean(EventRegistry.class)
                            .handler(DELETE_HANDLER)
                            .orElseThrow()
                            .prepare(expired.event());
            assertThat(original(fixture)).doesNotExist();
            assertThat(
                            context.getBean(JdbcEventStore.class)
                                    .complete(
                                            expired,
                                            () -> {
                                                var result = prepared.apply();
                                                clock.advance(
                                                        context.getBean(
                                                                        EventDeliveryProperties
                                                                                .class)
                                                                .leaseDuration()
                                                                .plusSeconds(1));
                                                return result;
                                            }))
                    .isFalse();
            assertHidden(context, fixture);
            assertThat(indexCounts(context, fixture)).isEqualTo(before);
            assertThat(
                            jdbc(context)
                                    .sql(
                                            "SELECT active_indexing_version FROM documents WHERE document_id = :id")
                                    .param("id", fixture.id())
                                    .query(Long.class)
                                    .single())
                    .isEqualTo(7);
            assertThat(payloadCount(context, fixture, "IndexPublished")).isEqualTo(1);
            assertThat(
                            jdbc(context)
                                    .sql(
                                            "SELECT state FROM event_deliveries WHERE delivery_id = :id")
                                    .param("id", late.lease().deliveryId())
                                    .query(String.class)
                                    .single())
                    .isEqualTo("RUNNING");
            assertThat(keys(context, fixture)).isEqualTo(fences);
        }
        try (var context = start(true)) {
            var store = context.getBean(JdbcEventStore.class);
            // Reap both expired leases; retries become eligible after bounded backoff.
            store.claim(UUID.randomUUID());
            store.claim(UUID.randomUUID());
            clock.advance(
                    context.getBean(EventDeliveryProperties.class).maxBackoff().plusSeconds(1));
            var replacement = claimCleanup(context);
            assertThat(replacement.token()).isNotEqualTo(expired.token());
            context.getBean(EventDispatcher.class).dispatch(replacement);
            assertCleaned(context, fixture);
            assertThat(
                            store.complete(
                                    expired,
                                    () -> {
                                        throw new AssertionError("Expired cleanup ran");
                                    }))
                    .isFalse();
            assertThat(keys(context, fixture)).isEqualTo(fences);
            assertThat(attempts(context, fixture)).containsExactly("EXPIRED", "COMPLETED");
        }
    }

    @Test
    void filesystemFailurePreservesHiddenPublishedDataUntilTheOriginalCanBeRemoved()
            throws Exception {
        try (var context = start(true)) {
            var fixture = published(context);
            var before = indexCounts(context, fixture);
            var backup = storage.resolve("test-original-backup");
            Files.move(original(fixture), backup);
            Files.createDirectory(original(fixture));
            var obstruction =
                    Files.writeString(
                            original(fixture).resolve("obstruction"), "synthetic obstruction");
            try {
                requestDeletion(context, fixture);
                context.getBean(EventDispatcher.class).dispatch(claimCleanup(context));
                assertThat(deliveryState(context, fixture)).isEqualTo("PENDING");
                assertHidden(context, fixture);
                assertThat(indexCounts(context, fixture)).isEqualTo(before);
                assertThat(payloadCount(context, fixture, "IndexPublished")).isEqualTo(1);
                assertThat(Files.readAllBytes(backup)).isEqualTo(CONTENT);
            } finally {
                Files.delete(obstruction);
                Files.delete(original(fixture));
                Files.move(backup, original(fixture));
            }
            clock.advance(Duration.ofSeconds(2));
            context.getBean(EventDispatcher.class).dispatch(claimCleanup(context));
            assertCleaned(context, fixture);
            assertThat(attempts(context, fixture)).containsExactly("FAILED", "COMPLETED");
        }
    }

    private Fixture published(ConfigurableApplicationContext context) throws Exception {
        var fixture = upload(context);
        extract(context, fixture);
        // Index versions are independent of extraction/deletion versions (1/2).
        clock.advance(Duration.ofMillis(1));
        context.getBean(DocumentIndexingService.class)
                .request(fixture.id(), 7, UUID.randomUUID())
                .orElseThrow();
        for (var handler :
                List.of(
                        "documents.request-index.v1",
                        "documents.prepare-passages.v1",
                        "documents.embed-passages.v1")) {
            var lease =
                    context.getBean(JdbcEventStore.class).claim(UUID.randomUUID()).orElseThrow();
            assertThat(lease.handlerId()).isEqualTo(handler);
            context.getBean(EventDispatcher.class).dispatch(lease);
        }
        assertThat(
                        jdbc(context)
                                .sql(
                                        "SELECT active_indexing_version FROM documents WHERE document_id = :id")
                                .param("id", fixture.id())
                                .query(Long.class)
                                .single())
                .isEqualTo(7);
        assertThat(payloadCount(context, fixture, "IndexPublished")).isEqualTo(1);
        return fixture;
    }

    private LateWork prepareLateWork(
            ConfigurableApplicationContext context, Fixture fixture, LateStage stage)
            throws Exception {
        context.getBean(DocumentIndexingService.class)
                .request(fixture.id(), 8, UUID.randomUUID())
                .orElseThrow();
        var store = context.getBean(JdbcEventStore.class);
        var passage = store.claim(UUID.randomUUID()).orElseThrow();
        assertThat(passage.handlerId()).isEqualTo("documents.prepare-passages.v1");
        context.getBean(EventDispatcher.class).dispatch(passage);
        var embedding = store.claim(UUID.randomUUID()).orElseThrow();
        assertThat(embedding.handlerId()).isEqualTo("documents.embed-passages.v1");
        var handler =
                context.getBean(EventRegistry.class).handler(embedding.handlerId()).orElseThrow();
        if (stage == LateStage.BATCH) {
            var captured = new AtomicReference<EventHandler.Effect>();
            handler.prepare(
                    embedding.event(),
                    effect -> {
                        captured.set(effect);
                        return Optional.empty();
                    });
            assertThat(captured.get()).isNotNull();
            return new LateWork(embedding, captured.get());
        }
        var effect =
                handler.prepare(
                        embedding.event(), progress -> store.checkpoint(embedding, progress));
        if (stage == LateStage.PUBLICATION) return new LateWork(embedding, effect);
        assertThat(store.complete(embedding, () -> EventHandler.Outcome.COMPLETED)).isTrue();
        transaction(context)
                .executeWithoutResult(
                        status ->
                                context.getBean(EventPublisher.class)
                                        .publish(
                                                new EventEnvelope(
                                                        UUID.randomUUID(),
                                                        new EventEnvelope.Type(
                                                                "IndexPublicationRequested", 1),
                                                        fixture.id(),
                                                        8,
                                                        clock.instant(),
                                                        embedding.event().correlationId(),
                                                        embedding.event().eventId(),
                                                        embedding.event().traceParent(),
                                                        Map.of())));
        var recovery = store.claim(UUID.randomUUID()).orElseThrow();
        assertThat(recovery.handlerId()).isEqualTo("documents.publish-index.v1");
        return new LateWork(
                recovery,
                context.getBean(EventRegistry.class)
                        .handler(recovery.handlerId())
                        .orElseThrow()
                        .prepare(recovery.event()));
    }

    private static DeliveryLease claimCleanup(ConfigurableApplicationContext context) {
        for (int count = 0; count < 10; count++) {
            var lease = context.getBean(JdbcEventStore.class).claim(UUID.randomUUID());
            if (lease.isEmpty()) continue;
            if (lease.orElseThrow().handlerId().equals(DELETE_HANDLER)) return lease.orElseThrow();
            context.getBean(EventDispatcher.class).dispatch(lease.orElseThrow());
        }
        throw new AssertionError("Cleanup delivery was not claimable within the fixture bound");
    }

    private static TransactionTemplate transaction(ConfigurableApplicationContext context) {
        var transaction =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        transaction.setTimeout(5);
        return transaction;
    }

    private static List<Long> indexCounts(ConfigurableApplicationContext context, Fixture fixture) {
        return List.of("document_index_versions", "document_passages", "document_embeddings")
                .stream()
                .map(table -> count(context, table, fixture.id()))
                .toList();
    }

    private static List<UUID> keys(ConfigurableApplicationContext context, Fixture fixture) {
        return jdbc(context)
                .sql("SELECT event_id FROM event_keys WHERE document_id = :id ORDER BY event_id")
                .param("id", fixture.id())
                .query(UUID.class)
                .list();
    }

    private static long payloadCount(
            ConfigurableApplicationContext context, Fixture fixture, String type) {
        return jdbc(context)
                .sql(
                        "SELECT count(*) FROM domain_events JOIN event_keys USING (event_id) WHERE document_id = :id AND event_type = :type")
                .param("id", fixture.id())
                .param("type", type)
                .query(Long.class)
                .single();
    }

    private static boolean claimAndDispatch(
            ConfigurableApplicationContext context, CyclicBarrier ready, CyclicBarrier claimed)
            throws Exception {
        ready.await(10, TimeUnit.SECONDS);
        var lease = context.getBean(JdbcEventStore.class).claim(UUID.randomUUID());
        // Neither runner dispatches until both competing claim transactions have finished.
        claimed.await(10, TimeUnit.SECONDS);
        lease.ifPresent(context.getBean(EventDispatcher.class)::dispatch);
        return lease.isPresent();
    }

    private Fixture upload(ConfigurableApplicationContext context) throws Exception {
        var id =
                context.getBean(DocumentAcceptanceService.class)
                        .accept(
                                "notes.md",
                                CONTENT.length,
                                new ByteArrayInputStream(CONTENT),
                                UUID.randomUUID())
                        .documentId();
        var storageId =
                jdbc(context)
                        .sql("SELECT storage_key FROM documents WHERE document_id = :id")
                        .param("id", id)
                        .query(UUID.class)
                        .single();
        return new Fixture(id, storageId);
    }

    private static void extract(ConfigurableApplicationContext context, Fixture fixture) {
        var accepted = context.getBean(JdbcEventStore.class).claim(UUID.randomUUID()).orElseThrow();
        assertThat(accepted.event().documentId()).isEqualTo(fixture.id());
        context.getBean(EventDispatcher.class).dispatch(accepted);
        assertThat(
                        context.getBean(DocumentInspectionService.class)
                                .find(fixture.id())
                                .orElseThrow()
                                .state())
                .isEqualTo(ExtractionState.EXTRACTED);
    }

    private static void requestDeletion(ConfigurableApplicationContext context, Fixture fixture) {
        assertThat(
                        context.getBean(DocumentDeletionService.class)
                                .request(fixture.id(), UUID.randomUUID()))
                .isTrue();
        assertHidden(context, fixture);
    }

    private void assertCleaned(ConfigurableApplicationContext context, Fixture fixture) {
        assertHidden(context, fixture);
        assertThat(original(fixture)).doesNotExist();
        for (var table :
                List.of(
                        "documents",
                        "document_extractions",
                        "document_text_lines",
                        "document_index_versions",
                        "document_passages",
                        "document_embeddings")) {
            assertThat(count(context, table, fixture.id())).as(table).isZero();
        }
        assertThat(deliveryState(context, fixture)).isEqualTo("COMPLETED");
        assertThat(
                        jdbc(context)
                                .sql(
                                        """
                SELECT count(*) FROM event_deliveries d JOIN event_keys k USING (event_id)
                WHERE k.document_id = :id
                """)
                                .param("id", fixture.id())
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
        assertThat(
                        jdbc(context)
                                .sql(
                                        """
                SELECT count(*) FROM domain_events e JOIN event_keys k USING (event_id)
                WHERE k.document_id = :id AND k.event_type <> 'DocumentDeletionRequested'
                """)
                                .param("id", fixture.id())
                                .query(Integer.class)
                                .single())
                .isZero();
        assertThat(eventCount(context, fixture, "DocumentDeletionRequested")).isEqualTo(1);
    }

    private static void assertHidden(ConfigurableApplicationContext context, Fixture fixture) {
        var inspection = context.getBean(DocumentInspectionService.class);
        assertThat(inspection.find(fixture.id())).isEmpty();
        assertThat(inspection.list(0).documents())
                .noneMatch(document -> document.documentId().equals(fixture.id()));
    }

    private Path original(Fixture fixture) {
        return storage.resolve(fixture.storageId() + ".original");
    }

    private static long count(ConfigurableApplicationContext context, String table, UUID id) {
        // Table names come only from this test's fixed literals, never request/document input.
        return jdbc(context)
                .sql("SELECT count(*) FROM " + table + " WHERE document_id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private static int eventCount(
            ConfigurableApplicationContext context, Fixture fixture, String type) {
        return jdbc(context)
                .sql(
                        "SELECT count(*) FROM event_keys WHERE document_id = :id AND event_type = :type")
                .param("id", fixture.id())
                .param("type", type)
                .query(Integer.class)
                .single();
    }

    private static String deliveryState(ConfigurableApplicationContext context, Fixture fixture) {
        return jdbc(context)
                .sql(
                        """
                SELECT d.state FROM event_deliveries d JOIN event_keys k USING (event_id)
                WHERE k.document_id = :id AND d.handler_id = :handler
                """)
                .param("id", fixture.id())
                .param("handler", DELETE_HANDLER)
                .query(String.class)
                .single();
    }

    private static List<String> attempts(ConfigurableApplicationContext context, Fixture fixture) {
        return jdbc(context)
                .sql(
                        """
                SELECT a.outcome FROM event_delivery_attempts a
                JOIN event_deliveries d USING (delivery_id) JOIN event_keys k USING (event_id)
                WHERE k.document_id = :id AND d.handler_id = :handler ORDER BY a.attempt
                """)
                .param("id", fixture.id())
                .param("handler", DELETE_HANDLER)
                .query(String.class)
                .list();
    }

    private static JdbcClient jdbc(ConfigurableApplicationContext context) {
        return context.getBean(JdbcClient.class);
    }

    private ConfigurableApplicationContext start() {
        return start(false);
    }

    private ConfigurableApplicationContext start(boolean indexed) {
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(
                        context -> {
                            context.getBeanFactory().registerSingleton("deletionTestClock", clock);
                            if (indexed)
                                context.getBeanFactory()
                                        .registerSingleton("deletionTestEmbeddings", embeddings);
                        })
                .run(
                        "--spring.config.import=",
                        "--secondbrain.events.polling-enabled=false",
                        "--secondbrain.documents.cleanup.enabled=false",
                        "--secondbrain.documents.indexing.enabled=" + indexed,
                        "--secondbrain.embeddings.enabled=" + indexed,
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

    private record Fixture(UUID id, UUID storageId) {}

    private record LateWork(DeliveryLease lease, EventHandler.Effect effect) {}

    enum LateStage {
        BATCH,
        PUBLICATION,
        RECOVERY
    }
}
