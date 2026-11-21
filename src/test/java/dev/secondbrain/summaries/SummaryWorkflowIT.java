package dev.secondbrain.summaries;

import static dev.secondbrain.TestHttp.client;
import static dev.secondbrain.TestHttp.get;
import static dev.secondbrain.TestHttp.origin;
import static dev.secondbrain.TestHttp.post;
import static dev.secondbrain.TestHttp.ready;
import static dev.secondbrain.TestHttp.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.TestDatabase;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;
import dev.secondbrain.questions.ChatFailure;
import dev.secondbrain.questions.ChatProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** The summary stage through the real event pipeline, with deterministic providers. */
@ExtendWith(OutputCaptureExtension.class)
class SummaryWorkflowIT {
    private static final EmbeddingModel MODEL = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
    private static final String NOTE = "ArrayList keeps values in order and permits duplicates.";
    @TempDir private Path storage;

    @Test
    void summarizesAfterIndexingRegeneratesOnRequestSurvivesQuotaAndLeavesWithTheDocument(
            CapturedOutput output) throws Exception {
        var models = new Models();
        try (var postgres = TestDatabase.create();
                var context = start(postgres, models);
                var client = client()) {
            var origin = origin(context);
            var id = upload(client, origin, "arraylist.md", NOTE.getBytes(StandardCharsets.UTF_8));
            ready(context, 1);
            // The stage runs on its own after publication: one chat request, one stored summary.
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(summaries(context)).isEqualTo(1));
            assertThat(models.chatCalls).hasValue(1);
            var page = get(client, origin, "/documents/" + id).body();
            assertThat(page)
                    .contains(
                            "First summary",
                            "href=\"#summary-source-1\"",
                            "id=\"summary-source-1\"",
                            "Regenerate summary");

            models.reply.set("Second summary [1].");
            assertThat(post(client, origin, "/documents/" + id + "/summary", "").statusCode())
                    .isEqualTo(303);
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () ->
                                    assertThat(get(client, origin, "/documents/" + id).body())
                                            .contains("Second summary"));

            // Exhausted allowance delays the stage; the document and its summary stay usable.
            models.quota.set(true);
            assertThat(post(client, origin, "/documents/" + id + "/summary", "").statusCode())
                    .isEqualTo(303);
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(models.chatCalls.get()).isGreaterThan(2));
            assertThat(get(client, origin, "/documents/" + id).body())
                    .contains("Second summary", "Ready");

            assertThat(post(client, origin, "/documents/" + id + "/delete", "").statusCode())
                    .isEqualTo(303);
            assertThat(get(client, origin, "/documents/" + id).statusCode()).isEqualTo(404);
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(summaries(context)).isZero());
        }
        assertThat(output).doesNotContain(NOTE, "First summary", "Second summary");
    }

    private static int summaries(ConfigurableApplicationContext context) {
        return context.getBean(JdbcClient.class)
                .sql("SELECT count(*) FROM document_summaries")
                .query(Integer.class)
                .single();
    }

    private ConfigurableApplicationContext start(TestDatabase postgres, Models models) {
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.SERVLET)
                .registerShutdownHook(false)
                .initializers(
                        context ->
                                context.getBeanFactory()
                                        .registerSingleton("summaryWorkflowModels", models))
                .run(
                        postgres.arguments(
                                "--secondbrain.telemetry.otlp-endpoint=",
                                "--server.address=127.0.0.1",
                                "--server.port=0",
                                "--secondbrain.questions.enabled=true",
                                "--secondbrain.embeddings.enabled=true",
                                "--secondbrain.summaries.enabled=true",
                                "--secondbrain.embeddings.batch-size=1",
                                "--secondbrain.events.polling-enabled=true",
                                "--secondbrain.events.concurrency=1",
                                "--secondbrain.events.claim-batch-size=1",
                                "--secondbrain.events.poll-interval=50ms",
                                "--secondbrain.events.initial-backoff=100ms",
                                "--secondbrain.events.max-backoff=200ms",
                                "--secondbrain.documents.storage-directory=" + storage));
    }

    private static final class Models implements EmbeddingProvider, ChatProvider {
        private final AtomicInteger chatCalls = new AtomicInteger();
        private final AtomicReference<String> reply = new AtomicReference<>("First summary [1].");
        private final AtomicBoolean quota = new AtomicBoolean();

        @Override
        public EmbeddingModel model() {
            return MODEL;
        }

        @Override
        public List<EmbeddingVector> embed(List<String> inputs) {
            return inputs.stream()
                    .map(
                            text -> {
                                var values = new float[1024];
                                values[0] = 1;
                                return new EmbeddingVector(MODEL, values);
                            })
                    .toList();
        }

        @Override
        public Reply generate(Request request) {
            chatCalls.incrementAndGet();
            assertThat(request.question()).isEqualTo(SummaryGenerator.REQUEST);
            if (quota.get()) {
                var failure = mock(ChatFailure.class);
                when(failure.reason()).thenReturn(ChatFailure.Reason.QUOTA_EXHAUSTED);
                when(failure.retryAt()).thenReturn(Optional.empty());
                throw failure;
            }
            return new Reply(
                    Outcome.ANSWERED,
                    reply.get(),
                    List.of(request.evidence().getFirst().passageId()));
        }
    }
}
