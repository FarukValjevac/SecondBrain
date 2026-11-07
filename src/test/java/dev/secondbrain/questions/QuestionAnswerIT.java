package dev.secondbrain.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.documents.DocumentAcceptanceService;
import dev.secondbrain.documents.DocumentDeletionService;
import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.PdfFixtures;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;
import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchScope;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real extraction/indexing/search and fresh PostgreSQL source reads; all model calls are fakes. */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class QuestionAnswerIT {
    private static final String PRIVATE = "citation-private-source-question-answer-name-sentinel";
    private static final EmbeddingModel MODEL = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir private Path storage;

    @AfterEach
    void clearIsolatedRowsOnlyAfterContextsAndWorkersClose() {
        JdbcClient.create(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()))
                .sql("TRUNCATE documents, event_keys CASCADE")
                .update();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsOnlySuppliedSourcesWithStableTextAndPhysicalPdfLocations(
            boolean pdf, CapturedOutput output) throws Exception {
        ChatProvider chat =
                request -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return new ChatProvider.Reply(
                            ChatProvider.Outcome.ANSWERED,
                            PRIVATE,
                            request.evidence().stream()
                                    .map(ChatProvider.Passage::passageId)
                                    .toList());
                };
        try (var context = start(chat)) {
            var id = upload(context, pdf);
            ready(context, 1);
            var answer =
                    context.getBean(QuestionAnswerService.class)
                            .answer(new SearchQuery(PRIVATE, SearchScope.selected(Set.of(id))));
            assertThat(answer.outcome()).isEqualTo(QuestionAnswerService.Outcome.ANSWERED);
            assertThat(answer.text()).isEqualTo(PRIVATE);
            assertThat(answer.citations()).hasSize(pdf ? 2 : 1);
            var documents = context.getBean(DocumentInspectionService.class);
            for (var citation : answer.citations()) {
                assertThat(citation.documentId()).isEqualTo(id);
                assertThat(documents.source(id, citation.passageId())).contains(citation);
                assertThat(citation.text()).contains(PRIVATE);
                assertThat(citation.indexingVersion()).isEqualTo(1);
                assertThat(citation.location().startLine()).isPositive();
            }
            if (pdf)
                assertThat(answer.citations())
                        .extracting(source -> source.location().pageNumber().orElseThrow())
                        .containsExactlyInAnyOrder(1, 3);
            else assertThat(answer.citations().getFirst().location().pageNumber()).isEmpty();
            assertThat(
                            context.getBean(MeterRegistry.class)
                                    .get("secondbrain.question.answers")
                                    .tag("outcome", "answered")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
        }
        assertThat(output).doesNotContain(PRIVATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cited", "uncited", "insufficient"})
    void deletionCommittedWhileChatIsBlockedDiscardsTheReplyAndPreventsNewSourceReads(
            String mode, CapturedOutput output) throws Exception {
        var arrived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var supplied = new AtomicReference<ChatProvider.Request>();
        ChatProvider blocked =
                request -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    supplied.set(request);
                    arrived.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    return mode.equals("insufficient")
                            ? new ChatProvider.Reply(
                                    ChatProvider.Outcome.INSUFFICIENT_INFORMATION, "", List.of())
                            : new ChatProvider.Reply(
                                    ChatProvider.Outcome.ANSWERED,
                                    PRIVATE,
                                    List.of(request.evidence().getFirst().passageId()));
                };
        try (var context = start(blocked)) {
            upload(context, false);
            upload(context, false);
            ready(context, 2);
            var executor =
                    new ThreadPoolExecutor(
                            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
            try {
                var future =
                        executor.submit(
                                () ->
                                        context.getBean(QuestionAnswerService.class)
                                                .answer(
                                                        new SearchQuery(
                                                                PRIVATE, SearchScope.all())));
                assertThat(arrived.await(10, TimeUnit.SECONDS)).isTrue();
                var evidence = supplied.get().evidence();
                assertThat(evidence).hasSize(2);
                var passage = evidence.get(mode.equals("uncited") ? 1 : 0).passageId();
                var owner =
                        context.getBean(JdbcClient.class)
                                .sql(
                                        "SELECT document_id FROM document_passages WHERE passage_id = :id")
                                .param("id", passage)
                                .query(UUID.class)
                                .single();
                assertThat(
                                context.getBean(DocumentDeletionService.class)
                                        .request(owner, UUID.randomUUID()))
                        .isTrue();
                assertThat(context.getBean(DocumentInspectionService.class).source(owner, passage))
                        .isEmpty();
                release.countDown();
                assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                        .isInstanceOfSatisfying(
                                ExecutionException.class,
                                failure ->
                                        assertThat(failure.getCause())
                                                .isInstanceOfSatisfying(
                                                        QuestionFailure.class,
                                                        questionFailure -> {
                                                            assertThat(questionFailure.stage())
                                                                    .isEqualTo(
                                                                            QuestionFailure.Stage
                                                                                    .SOURCES);
                                                            assertThat(questionFailure.reason())
                                                                    .isEqualTo(
                                                                            QuestionFailure.Reason
                                                                                    .SOURCE_UNAVAILABLE);
                                                            assertThat(questionFailure.getCause())
                                                                    .isNull();
                                                        }));
                assertThat(
                                context.getBean(DocumentInspectionService.class)
                                        .sources(java.util.Map.of(passage, owner)))
                        .isEmpty();
                assertThat(
                                context.getBean(MeterRegistry.class)
                                        .get("secondbrain.question.answers")
                                        .tag("outcome", "source_unavailable")
                                        .counter()
                                        .count())
                        .isEqualTo(1);
            } finally {
                release.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(output).doesNotContain(PRIVATE);
    }

    @Test
    void aRealStoredPassageOutsideThisQuestionsContextIsRejected() throws Exception {
        var foreign = new AtomicReference<UUID>();
        ChatProvider chat =
                request ->
                        new ChatProvider.Reply(
                                ChatProvider.Outcome.ANSWERED, PRIVATE, List.of(foreign.get()));
        try (var context = start(chat)) {
            var selected = upload(context, false);
            var other = upload(context, false);
            ready(context, 2);
            var passage =
                    context.getBean(JdbcClient.class)
                            .sql("SELECT passage_id FROM document_passages WHERE document_id = :id")
                            .param("id", other)
                            .query(UUID.class)
                            .single();
            foreign.set(passage);
            assertThat(context.getBean(DocumentInspectionService.class).source(other, passage))
                    .isPresent();
            assertThatThrownBy(
                            () ->
                                    context.getBean(QuestionAnswerService.class)
                                            .answer(
                                                    new SearchQuery(
                                                            PRIVATE,
                                                            SearchScope.selected(
                                                                    Set.of(selected)))))
                    .isInstanceOfSatisfying(
                            QuestionFailure.class,
                            failure -> {
                                assertThat(failure.stage())
                                        .isEqualTo(QuestionFailure.Stage.CITATIONS);
                                assertThat(failure.reason())
                                        .isEqualTo(QuestionFailure.Reason.INVALID_CITATION);
                            });
        }
    }

    private UUID upload(ConfigurableApplicationContext context, boolean pdf) throws Exception {
        byte[] bytes =
                pdf
                        ? PdfFixtures.text(PRIVATE + " source one.", "", PRIVATE + " source three.")
                        : (PRIVATE + " source.\n").getBytes(StandardCharsets.UTF_8);
        try (var stream = new ByteArrayInputStream(bytes)) {
            return context.getBean(DocumentAcceptanceService.class)
                    .accept(
                            PRIVATE + (pdf ? ".pdf" : ".md"),
                            bytes.length,
                            stream,
                            UUID.randomUUID())
                    .documentId();
        }
    }

    private static void ready(ConfigurableApplicationContext context, int count) {
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(
                                                context.getBean(JdbcClient.class)
                                                        .sql(
                                                                "SELECT count(*) FROM documents WHERE active_indexing_version IS NOT NULL")
                                                        .query(Integer.class)
                                                        .single())
                                        .isEqualTo(count));
    }

    private ConfigurableApplicationContext start(ChatProvider chat) {
        EmbeddingProvider embeddings =
                new EmbeddingProvider() {
                    @Override
                    public EmbeddingModel model() {
                        return MODEL;
                    }

                    @Override
                    public List<EmbeddingVector> embed(List<String> inputs) {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                .isFalse();
                        return inputs.stream()
                                .map(
                                        input -> {
                                            var values = new float[1024];
                                            values[0] = 1;
                                            return new EmbeddingVector(MODEL, values);
                                        })
                                .toList();
                    }
                };
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .initializers(
                        context -> {
                            context.getBeanFactory()
                                    .registerSingleton("citationTestEmbeddings", embeddings);
                            context.getBeanFactory().registerSingleton("citationTestChat", chat);
                            context.getBeanFactory()
                                    .registerSingleton(
                                            "citationTestClock",
                                            Clock.tick(Clock.systemUTC(), Duration.ofNanos(1000)));
                        })
                .run(
                        "--spring.config.import=",
                        "--secondbrain.telemetry.otlp-endpoint=",
                        "--secondbrain.questions.enabled=true",
                        "--secondbrain.embeddings.enabled=true",
                        "--secondbrain.embeddings.batch-size=1",
                        "--secondbrain.search.minimum-score=0.3",
                        "--secondbrain.events.polling-enabled=true",
                        "--secondbrain.events.concurrency=1",
                        "--secondbrain.events.claim-batch-size=1",
                        "--secondbrain.events.poll-interval=50ms",
                        "--secondbrain.documents.cleanup.enabled=false",
                        "--secondbrain.documents.indexing.reconcile-enabled=false",
                        "--secondbrain.documents.extraction.reconcile-enabled=false",
                        "--secondbrain.documents.deletion.reconcile-enabled=false",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--secondbrain.documents.storage-directory=" + storage);
    }
}
