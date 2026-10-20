package dev.secondbrain.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.documents.DocumentAcceptanceService;
import dev.secondbrain.documents.DocumentDeletionService;
import dev.secondbrain.documents.DocumentIndexingService;
import dev.secondbrain.documents.PdfFixtures;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Exercises the real registered stage and acknowledgment boundary against isolated PostgreSQL. */
@Testcontainers
class DocumentPassageDeliveryIT {
    private static final String HANDLER = "documents.prepare-passages.v1";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir private Path storage;
    private final MutableClock clock =
            new MutableClock(Instant.parse("2030-01-01T00:00:00.123456Z"));

    @AfterEach
    void clearOnlyContainerFixturesAfterContextsClose() {
        JdbcClient.create(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()))
                .sql("TRUNCATE documents, event_keys CASCADE")
                .update();
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void uploadsReachRealStoredPassagesWithOneCorrelatedDownstreamFact(Format format)
            throws Exception {
        try (var context = start()) {
            var lease = queuedPassages(context, format);
            assertUnprepared(context, lease);
            context.getBean(EventDispatcher.class).dispatch(lease);
            assertCompleted(context, lease);
            jdbc(context)
                    .sql(
                            """
                    SELECT k.schema_version, k.processing_version, e.correlation_id, e.causation_id, e.reference_ids
                    FROM event_keys k JOIN domain_events e USING (event_id)
                    WHERE k.document_id = :id AND k.event_type = 'PassagesPrepared'
                    """)
                    .param("id", lease.event().documentId())
                    .query(
                            (row, index) -> {
                                assertThat(row.getInt("schema_version")).isEqualTo(1);
                                assertThat(row.getLong("processing_version"))
                                        .isEqualTo(lease.event().processingVersion());
                                assertThat(row.getObject("correlation_id", UUID.class))
                                        .isEqualTo(lease.event().correlationId());
                                assertThat(row.getObject("causation_id", UUID.class))
                                        .isEqualTo(lease.event().eventId());
                                assertThat(row.getString("reference_ids")).isEqualTo("{}");
                                return true;
                            })
                    .single();
            var pages =
                    jdbc(context)
                            .sql(
                                    "SELECT DISTINCT page_number FROM document_passages WHERE page_number IS NOT NULL ORDER BY page_number")
                            .query(Integer.class)
                            .list();
            if (format == Format.PDF) assertThat(pages).containsExactly(1, 3);
            else assertThat(pages).isEmpty();
            // Embedding has no consumer yet; its durable fact must remain available, not
            // acknowledged.
            assertThat(
                            jdbc(context)
                                    .sql(
                                            """
                    SELECT count(*) FROM event_deliveries d JOIN event_keys k USING (event_id)
                    WHERE k.event_type = 'PassagesPrepared'
                    """)
                                    .query(Long.class)
                                    .single())
                    .isZero();
            assertThat(
                            jdbc(context)
                                    .sql(
                                            "SELECT count(*) FROM event_deliveries WHERE state = 'COMPLETED'")
                                    .query(Long.class)
                                    .single())
                    .isEqualTo(3);
        }
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    void downstreamInsertAndCommitFailuresRollBackPassagesMetadataAndAcknowledgment(Fault fault)
            throws Exception {
        try (var context = start()) {
            var lease = queuedPassages(context, Format.MARKDOWN);
            var effect =
                    context.getBean(EventRegistry.class)
                            .handler(HANDLER)
                            .orElseThrow()
                            .prepare(lease.event());
            if (fault == Fault.INSERT) {
                jdbc(context)
                        .sql(
                                "ALTER TABLE domain_events ADD CONSTRAINT passage_test_fault CHECK (false) NOT VALID")
                        .update();
            } else {
                jdbc(context)
                        .sql(
                                "CREATE TABLE passage_allowed_types (event_type varchar(64) PRIMARY KEY)")
                        .update();
                jdbc(context)
                        .sql(
                                "INSERT INTO passage_allowed_types SELECT DISTINCT event_type FROM event_keys")
                        .update();
                jdbc(context)
                        .sql(
                                "ALTER TABLE event_keys ADD CONSTRAINT passage_test_fault FOREIGN KEY (event_type) REFERENCES passage_allowed_types(event_type) DEFERRABLE INITIALLY DEFERRED")
                        .update();
            }
            try {
                assertThatThrownBy(
                                () -> context.getBean(JdbcEventStore.class).complete(lease, effect))
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertUnprepared(context, lease);
                assertThat(state(context, lease)).isEqualTo("RUNNING");
            } finally {
                jdbc(context)
                        .sql(
                                fault == Fault.INSERT
                                        ? "ALTER TABLE domain_events DROP CONSTRAINT passage_test_fault"
                                        : "ALTER TABLE event_keys DROP CONSTRAINT passage_test_fault")
                        .update();
                if (fault == Fault.COMMIT)
                    jdbc(context).sql("DROP TABLE passage_allowed_types").update();
            }
            assertThat(context.getBean(JdbcEventStore.class).complete(lease, effect)).isTrue();
            assertCompleted(context, lease);
        }
    }

    @Test
    void leaseExpiryAfterTheEffectRollsBackAllStageEffectsAndANewLeaseCanFinish() throws Exception {
        try (var context = start()) {
            var lease = queuedPassages(context, Format.TXT);
            var effect =
                    context.getBean(EventRegistry.class)
                            .handler(HANDLER)
                            .orElseThrow()
                            .prepare(lease.event());
            var store = context.getBean(JdbcEventStore.class);
            assertThat(
                            store.complete(
                                    lease,
                                    () -> {
                                        assertThat(effect.apply())
                                                .isEqualTo(EventHandler.Outcome.COMPLETED);
                                        clock.advance(
                                                context.getBean(EventDeliveryProperties.class)
                                                        .leaseDuration()
                                                        .plusSeconds(1));
                                        return EventHandler.Outcome.COMPLETED;
                                    }))
                    .isFalse();
            assertUnprepared(context, lease);
            assertThat(state(context, lease)).isEqualTo("RUNNING");
            assertThat(store.claim(UUID.randomUUID())).isEmpty();
            clock.advance(
                    context.getBean(EventDeliveryProperties.class).maxBackoff().plusSeconds(1));
            var fresh = store.claim(UUID.randomUUID()).orElseThrow();
            assertThat(fresh.deliveryId()).isEqualTo(lease.deliveryId());
            assertThat(fresh.token()).isNotEqualTo(lease.token());
            assertThat(store.complete(lease, effect)).isFalse();
            context.getBean(EventDispatcher.class).dispatch(fresh);
            assertCompleted(context, fresh);
        }
    }

    @ParameterizedTest
    @EnumSource(Stale.class)
    void reindexingSourceChangesAndDeletionCannotPublishAPreparedOldStage(Stale change)
            throws Exception {
        try (var context = start()) {
            var lease = queuedPassages(context, Format.TXT);
            var effect =
                    context.getBean(EventRegistry.class)
                            .handler(HANDLER)
                            .orElseThrow()
                            .prepare(lease.event());
            switch (change) {
                case REINDEX ->
                        context.getBean(DocumentIndexingService.class)
                                .requestNext(lease.event().documentId(), UUID.randomUUID())
                                .orElseThrow();
                case SOURCE ->
                        jdbc(context)
                                .sql(
                                        "UPDATE documents SET processing_version = 2 WHERE document_id = :id")
                                .param("id", lease.event().documentId())
                                .update();
                case DELETE ->
                        context.getBean(DocumentDeletionService.class)
                                .request(lease.event().documentId(), UUID.randomUUID());
            }
            assertThat(context.getBean(JdbcEventStore.class).complete(lease, effect)).isTrue();
            assertThat(state(context, lease)).isEqualTo("SKIPPED");
            assertUnprepared(context, lease);
        }
    }

    @Test
    void anIncompleteStoredSourceFailsPermanentlyWithoutAcknowledgingPassageSuccess()
            throws Exception {
        try (var context = start()) {
            var lease = queuedPassages(context, Format.TXT);
            jdbc(context)
                    .sql("DELETE FROM document_text_lines WHERE document_id = :id")
                    .param("id", lease.event().documentId())
                    .update();
            context.getBean(EventDispatcher.class).dispatch(lease);
            assertThat(state(context, lease)).isEqualTo("FAILED");
            assertThat(
                            jdbc(context)
                                    .sql(
                                            "SELECT failure_code FROM event_deliveries WHERE delivery_id = :id")
                                    .param("id", lease.deliveryId())
                                    .query(String.class)
                                    .single())
                    .isEqualTo("HANDLER_PERMANENT");
            assertUnprepared(context, lease);
            assertThat(context.getBean(JdbcEventStore.class).claim(UUID.randomUUID())).isEmpty();
        }
    }

    private DeliveryLease queuedPassages(ConfigurableApplicationContext context, Format format)
            throws Exception {
        var bytes =
                format == Format.PDF
                        ? PdfFixtures.text("First page", "", "Third page")
                        : "# Study notes\nCafé 📚\n".getBytes(StandardCharsets.UTF_8);
        var id =
                context.getBean(DocumentAcceptanceService.class)
                        .accept(
                                "notes." + format.extension,
                                bytes.length,
                                new ByteArrayInputStream(bytes),
                                UUID.randomUUID())
                        .documentId();
        var store = context.getBean(JdbcEventStore.class);
        for (var handler : List.of("documents.extract-text.v1", "documents.request-index.v1")) {
            var lease = store.claim(UUID.randomUUID()).orElseThrow();
            assertThat(lease.handlerId()).isEqualTo(handler);
            assertThat(lease.event().documentId()).isEqualTo(id);
            context.getBean(EventDispatcher.class).dispatch(lease);
            assertThat(state(context, lease)).isEqualTo("COMPLETED");
        }
        var lease = store.claim(UUID.randomUUID()).orElseThrow();
        assertThat(lease.handlerId()).isEqualTo(HANDLER);
        assertThat(lease.event().documentId()).isEqualTo(id);
        return lease;
    }

    private static void assertCompleted(
            ConfigurableApplicationContext context, DeliveryLease lease) {
        assertThat(state(context, lease)).isEqualTo("COMPLETED");
        long count = passageCount(context, lease);
        assertThat(count).isGreaterThan(0);
        assertThat(
                        jdbc(context)
                                .sql(
                                        """
                SELECT passage_count FROM document_index_versions
                WHERE document_id = :id AND indexing_version = :version AND passage_configuration_version = 1
                """)
                                .param("id", lease.event().documentId())
                                .param("version", lease.event().processingVersion())
                                .query(Long.class)
                                .single())
                .isEqualTo(count);
        assertThat(preparedEvents(context, lease)).isEqualTo(1);
    }

    private static void assertUnprepared(
            ConfigurableApplicationContext context, DeliveryLease lease) {
        assertThat(passageCount(context, lease)).isZero();
        assertThat(preparedEvents(context, lease)).isZero();
        assertThat(
                        jdbc(context)
                                .sql(
                                        """
                SELECT count(*) FROM document_index_versions WHERE document_id = :id
                AND indexing_version = :version AND passage_configuration_version IS NOT NULL
                """)
                                .param("id", lease.event().documentId())
                                .param("version", lease.event().processingVersion())
                                .query(Long.class)
                                .single())
                .isZero();
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

    private static long preparedEvents(
            ConfigurableApplicationContext context, DeliveryLease lease) {
        return jdbc(context)
                .sql(
                        "SELECT count(*) FROM event_keys WHERE document_id = :id AND processing_version = :version AND event_type = 'PassagesPrepared'")
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

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(
                        context ->
                                context.getBeanFactory()
                                        .registerSingleton("passageTestClock", clock))
                .run(
                        "--spring.config.import=",
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

    enum Format {
        TXT("txt"),
        MARKDOWN("md"),
        PDF("pdf");
        final String extension;

        Format(String extension) {
            this.extension = extension;
        }
    }

    enum Fault {
        INSERT,
        COMMIT
    }

    enum Stale {
        REINDEX,
        SOURCE,
        DELETE
    }
}
