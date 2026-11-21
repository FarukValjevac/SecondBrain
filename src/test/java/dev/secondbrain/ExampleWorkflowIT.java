package dev.secondbrain;

import static dev.secondbrain.TestHttp.client;
import static dev.secondbrain.TestHttp.encode;
import static dev.secondbrain.TestHttp.get;
import static dev.secondbrain.TestHttp.origin;
import static dev.secondbrain.TestHttp.post;
import static dev.secondbrain.TestHttp.ready;
import static dev.secondbrain.TestHttp.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.secondbrain.documents.DocxFixtures;
import dev.secondbrain.documents.PdfFixtures;
import dev.secondbrain.embeddings.EmbeddingFailure;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;
import dev.secondbrain.questions.ChatProvider;

import io.micrometer.core.instrument.MeterRegistry;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The documented example workflow from an empty database through real HTTP. Deterministic providers
 * stand in for Cloudflare, so these checks make no live request and spend no allowance.
 */
@ExtendWith(OutputCaptureExtension.class)
class ExampleWorkflowIT {
    private static final EmbeddingModel MODEL = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
    private static final String ARRAYLIST_NAME = "arraylist-notes.md";
    private static final String HASHSET_NAME = "hashset-notes.txt";
    private static final String CARD_NAME = "practice-card.pdf";
    private static final String WORD_NAME = "linkedlist-notes.docx";
    private static final String WORD_FACT = "LinkedList addFirst inserts at the front.";
    private static final String PAGE_NAME = "review-plan.html";
    private static final String PAGE_FACT = "Review the notes after three days.";
    private static final String ARRAYLIST_FACT = "ArrayList keeps values in list order";
    private static final String ANSWER = "ArrayList permits duplicates.";
    private static final String BROKEN_NAME = "broken-encoding.txt";
    private static final String PROVIDER_BODY = "provider-body-sentinel";
    private static final String QUESTION = "Does ArrayList allow duplicates?";
    private static final Pattern SOURCE_LINK =
            Pattern.compile("href=\"(/documents/[0-9a-f-]{36}/passages/[0-9a-f-]{36})\"");

    @TempDir private Path storage;

    @Test
    void emptyDatabaseRunsUploadProcessingSearchQuestionsSourcesAndDeletion(CapturedOutput output)
            throws Exception {
        try (var postgres = TestDatabase.create()) {
            // A fresh container has no schema: every table below must come from Flyway alone.
            assertThat(tableCount(postgres)).isZero();
            var models = new Models();
            try (var context = start(postgres, models);
                    var client = client()) {
                var origin = origin(context);
                var flyway = context.getBean(Flyway.class);
                assertThat(flyway.info().pending()).isEmpty();
                assertThat(flyway.info().applied()).hasSize(20);
                assertThat(tableCount(postgres)).isPositive();
                for (var path :
                        List.of(
                                "/actuator/health",
                                "/actuator/health/liveness",
                                "/actuator/health/readiness")) {
                    var health = get(client, origin, path);
                    assertThat(health.statusCode()).isEqualTo(200);
                    assertThat(health.body()).contains("UP");
                }

                var arrayList =
                        upload(
                                client,
                                origin,
                                ARRAYLIST_NAME,
                                ("# Java study notes\n\n"
                                                + ARRAYLIST_FACT
                                                + " and permits duplicates.\n")
                                        .getBytes(StandardCharsets.UTF_8));
                upload(
                        client,
                        origin,
                        HASHSET_NAME,
                        "HashSet stores each distinct value only once.\n"
                                .getBytes(StandardCharsets.UTF_8));
                upload(
                        client,
                        origin,
                        CARD_NAME,
                        PdfFixtures.text("Practice card: the practice score counts answers."));
                var word = upload(client, origin, WORD_NAME, DocxFixtures.paragraphs(WORD_FACT));
                var page =
                        upload(
                                client,
                                origin,
                                PAGE_NAME,
                                ("<html><head><script>hidden()</script></head><body><h1>Plan</h1><p>"
                                                + PAGE_FACT
                                                + "</p></body></html>")
                                        .getBytes(StandardCharsets.UTF_8));
                ready(context, 5);

                var library = get(client, origin, "/");
                assertThat(library.body())
                        .contains(ARRAYLIST_NAME, HASHSET_NAME, CARD_NAME, WORD_NAME, PAGE_NAME);
                assertThat(get(client, origin, "/documents/" + word).body()).contains(WORD_FACT);
                assertThat(get(client, origin, "/documents/" + page).body())
                        .contains(PAGE_FACT)
                        .doesNotContain("hidden()");
                var detail = get(client, origin, "/documents/" + arrayList);
                assertThat(detail.statusCode()).isEqualTo(200);
                assertThat(detail.body()).contains("Ready", ARRAYLIST_FACT);
                // A ready document links to a question limited to itself, without asking yet.
                assertThat(detail.body()).contains("href=\"/questions?documentId=" + arrayList);
                var scoped = get(client, origin, "/questions?documentId=" + arrayList);
                assertThat(scoped.body())
                        .contains("name=\"documentId\" value=\"" + arrayList + "\" checked");
                assertThat(models.chatCalls.get()).isZero();

                var search =
                        post(client, origin, "/search", "q=" + encode(QUESTION) + "&scope=all");
                assertThat(search.statusCode()).isEqualTo(200);
                assertThat(search.body()).contains(ARRAYLIST_NAME, ARRAYLIST_FACT);
                var searchLinks = sourceLinks(search);
                assertThat(searchLinks).hasSize(1);
                assertThat(get(client, origin, searchLinks.getFirst()).statusCode()).isEqualTo(200);
                assertThat(models.chatCalls.get()).isZero();

                var answer =
                        post(
                                client,
                                origin,
                                "/questions",
                                "q=" + encode(QUESTION) + "&scope=all&action=ask");
                assertThat(answer.statusCode()).isEqualTo(200);
                assertThat(answer.body()).contains(ANSWER, ARRAYLIST_FACT);
                var citations = sourceLinks(answer);
                assertThat(citations).hasSize(1);
                assertThat(get(client, origin, citations.getFirst()).statusCode()).isEqualTo(200);
                assertThat(models.chatCalls.get()).isEqualTo(1);

                // Unrelated evidence is never sent to the chat provider.
                var absent =
                        post(
                                client,
                                origin,
                                "/questions",
                                "q=" + encode("What is the weather?") + "&scope=all&action=ask");
                assertThat(absent.body()).contains("Insufficient information");
                assertThat(absent.body()).doesNotContain(ANSWER);
                assertThat(models.chatCalls.get()).isEqualTo(1);

                int originals = files(storage);
                assertThat(post(client, origin, "/documents/" + arrayList + "/delete", ""))
                        .satisfies(deleted -> assertThat(deleted.statusCode()).isEqualTo(303));
                assertThat(get(client, origin, "/").body()).doesNotContain(ARRAYLIST_NAME);
                assertThat(get(client, origin, citations.getFirst()).statusCode()).isEqualTo(404);
                var afterDeletion =
                        post(client, origin, "/search", "q=" + encode(QUESTION) + "&scope=all");
                assertThat(afterDeletion.body()).doesNotContain(ARRAYLIST_FACT);
                await().atMost(Duration.ofSeconds(20))
                        .untilAsserted(
                                () -> {
                                    assertThat(count(context, "document_passages", arrayList))
                                            .isZero();
                                    assertThat(count(context, "document_embeddings", arrayList))
                                            .isZero();
                                    assertThat(files(storage)).isEqualTo(originals - 1);
                                });
            }
        }
        assertThat(output)
                .doesNotContain(
                        ARRAYLIST_NAME,
                        HASHSET_NAME,
                        CARD_NAME,
                        WORD_NAME,
                        WORD_FACT,
                        PAGE_NAME,
                        PAGE_FACT,
                        ARRAYLIST_FACT,
                        ANSWER,
                        PROVIDER_BODY);
    }

    @Test
    void failedAndRetriedDeliveriesLeaveSafeDiagnosticSignals(CapturedOutput output)
            throws Exception {
        try (var postgres = TestDatabase.create()) {
            var models = new Models();
            try (var context = start(postgres, models);
                    var client = client()) {
                var origin = origin(context);
                var meters = context.getBean(MeterRegistry.class);

                // The first embedding batch fails transiently; the same delivery retries itself.
                models.failNextPassageEmbedding.set(true);
                upload(
                        client,
                        origin,
                        ARRAYLIST_NAME,
                        (ARRAYLIST_FACT + " and permits duplicates.\n")
                                .getBytes(StandardCharsets.UTF_8));
                ready(context, 1);
                assertThat(attempts(meters, "retry")).isEqualTo(1);
                assertThat(count(context, "event_delivery_retries")).isZero();

                // Invalid bytes fail extraction permanently until an explicit manual retry.
                var broken = upload(client, origin, BROKEN_NAME, new byte[] {(byte) 0xC3, 0x28});
                await().atMost(Duration.ofSeconds(20))
                        .untilAsserted(() -> assertThat(failedDeliveries(context)).isEqualTo(1));
                assertThat(get(client, origin, "/").body()).contains(BROKEN_NAME, "Failed");
                var detail = get(client, origin, "/documents/" + broken);
                assertThat(detail.body()).contains("The document must use valid UTF-8 encoding.");
                assertThat(attempts(meters, "failed")).isEqualTo(1);
                await().atMost(Duration.ofSeconds(15))
                        .untilAsserted(
                                () ->
                                        assertThat(
                                                        meters.get("secondbrain.event.backlog")
                                                                .tag("state", "failed")
                                                                .gauge()
                                                                .value())
                                                .isEqualTo(1.0));

                var delivery = failedDelivery(context);
                var retry =
                        post(
                                client,
                                origin,
                                "/documents/" + broken + "/retry",
                                "deliveryId=" + delivery + "&cycle=0");
                assertThat(retry.statusCode()).isEqualTo(303);
                await().atMost(Duration.ofSeconds(20))
                        .untilAsserted(
                                () -> {
                                    assertThat(count(context, "event_delivery_retries"))
                                            .isEqualTo(1);
                                    assertThat(failedDeliveries(context)).isEqualTo(1);
                                    assertThat(attempts(meters, "failed")).isEqualTo(2);
                                });
                assertThat(
                                meters.get("secondbrain.document.processing.retries")
                                        .tag("outcome", "requested")
                                        .counter()
                                        .count())
                        .isEqualTo(1.0);
                // A failed delivery is a degraded document, not an unready application.
                assertThat(get(client, origin, "/actuator/health/readiness").statusCode())
                        .isEqualTo(200);
            }
        }
        assertThat(output)
                .doesNotContain(ARRAYLIST_NAME, BROKEN_NAME, ARRAYLIST_FACT, PROVIDER_BODY);
    }

    /** Deterministic stand-in for both hosted capabilities; no network request is possible. */
    private static final class Models implements EmbeddingProvider, ChatProvider {
        private final AtomicBoolean failNextPassageEmbedding = new AtomicBoolean();
        private final AtomicInteger chatCalls = new AtomicInteger();

        @Override
        public EmbeddingModel model() {
            return MODEL;
        }

        @Override
        public List<EmbeddingVector> embed(List<String> inputs) {
            // Only stored passages fail; question text is never part of an uploaded document.
            boolean passage = inputs.getFirst().contains(ARRAYLIST_FACT);
            if (passage && failNextPassageEmbedding.compareAndSet(true, false)) {
                var failure = mock(EmbeddingFailure.class);
                when(failure.reason()).thenReturn(EmbeddingFailure.Reason.UNAVAILABLE);
                when(failure.retryable()).thenReturn(true);
                when(failure.retryAt()).thenReturn(Optional.empty());
                when(failure.getMessage()).thenReturn(PROVIDER_BODY);
                throw failure;
            }
            return inputs.stream().map(Models::vector).toList();
        }

        /** One orthogonal axis per topic keeps similarity exact and independent of any model. */
        private static EmbeddingVector vector(String text) {
            var values = new float[1024];
            values[
                            text.contains("weather")
                                    ? 3
                                    : text.contains("ArrayList")
                                            ? 0
                                            : text.contains("HashSet") ? 1 : 2] =
                    1;
            return new EmbeddingVector(MODEL, values);
        }

        @Override
        public Reply generate(Request request) {
            chatCalls.incrementAndGet();
            return new Reply(
                    Outcome.ANSWERED,
                    ANSWER,
                    request.evidence().stream()
                            .filter(passage -> passage.text().contains("ArrayList"))
                            .map(Passage::passageId)
                            .toList());
        }
    }

    private ConfigurableApplicationContext start(TestDatabase postgres, Models models) {
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.SERVLET)
                .registerShutdownHook(false)
                .initializers(
                        context ->
                                context.getBeanFactory()
                                        .registerSingleton("exampleWorkflowModels", models))
                .run(
                        postgres.arguments(
                                "--secondbrain.telemetry.otlp-endpoint=",
                                "--server.address=127.0.0.1",
                                "--server.port=0",
                                "--secondbrain.questions.enabled=true",
                                "--secondbrain.embeddings.enabled=true",
                                "--secondbrain.embeddings.batch-size=1",
                                "--secondbrain.events.polling-enabled=true",
                                "--secondbrain.events.concurrency=1",
                                "--secondbrain.events.claim-batch-size=1",
                                "--secondbrain.events.poll-interval=50ms",
                                "--secondbrain.events.initial-backoff=100ms",
                                "--secondbrain.events.max-backoff=200ms",
                                "--secondbrain.documents.storage-directory=" + storage));
    }

    private static List<String> sourceLinks(HttpResponse<String> response) {
        return SOURCE_LINK
                .matcher(response.body())
                .results()
                .map(result -> result.group(1))
                .distinct()
                .toList();
    }

    private static int tableCount(TestDatabase postgres) {
        return JdbcClient.create(
                        new DriverManagerDataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword()))
                .sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")
                .query(Integer.class)
                .single();
    }

    // Table names are fixed test constants, never request or document input.
    private static int count(ConfigurableApplicationContext context, String table) {
        return context.getBean(JdbcClient.class)
                .sql("SELECT count(*) FROM " + table)
                .query(Integer.class)
                .single();
    }

    private static int count(ConfigurableApplicationContext context, String table, UUID document) {
        return context.getBean(JdbcClient.class)
                .sql("SELECT count(*) FROM " + table + " WHERE document_id = :id")
                .param("id", document)
                .query(Integer.class)
                .single();
    }

    private static int failedDeliveries(ConfigurableApplicationContext context) {
        return context.getBean(JdbcClient.class)
                .sql("SELECT count(*) FROM event_deliveries WHERE state = 'FAILED'")
                .query(Integer.class)
                .single();
    }

    private static UUID failedDelivery(ConfigurableApplicationContext context) {
        return context.getBean(JdbcClient.class)
                .sql("SELECT delivery_id FROM event_deliveries WHERE state = 'FAILED'")
                .query(UUID.class)
                .single();
    }

    private static double attempts(MeterRegistry meters, String outcome) {
        var counter =
                meters.find("secondbrain.event.delivery.attempts")
                        .tag("outcome", outcome)
                        .counter();
        return counter == null ? 0 : counter.count();
    }

    private static int files(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return (int) paths.filter(Files::isRegularFile).count();
        }
    }
}
