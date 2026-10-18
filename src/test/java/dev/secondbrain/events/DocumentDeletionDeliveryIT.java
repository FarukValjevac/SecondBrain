package dev.secondbrain.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.documents.DocumentAcceptanceService;
import dev.secondbrain.documents.DocumentDeletionService;
import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.ExtractionState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
        for (var table : List.of("documents", "document_extractions", "document_text_lines")) {
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
                WHERE k.document_id = :id AND k.processing_version < 2
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
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(
                        context ->
                                context.getBeanFactory()
                                        .registerSingleton("deletionTestClock", clock))
                .run(
                        "--spring.config.import=",
                        "--secondbrain.events.polling-enabled=false",
                        "--secondbrain.documents.cleanup.enabled=false",
                        "--secondbrain.documents.indexing.enabled=false",
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
}
