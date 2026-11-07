package dev.secondbrain.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchScope;
import dev.secondbrain.search.SearchService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;

class QuestionConfigurationTest {
    private final SearchService search = mock(SearchService.class);
    private final ChatProvider chat = mock(ChatProvider.class);
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            QuestionConfiguration.class, QuestionTelemetryConfiguration.class)
                    .withBean(
                            DocumentInspectionService.class,
                            () -> mock(DocumentInspectionService.class));

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "secondbrain.questions.enabled=true",
                "secondbrain.embeddings.enabled=true"
            })
    void partialOrDefaultConfigurationDoesNotRequireCollaborators(String setting) {
        var configured = setting.isEmpty() ? runner : runner.withPropertyValues(setting);
        configured.run(
                context ->
                        assertThat(context).hasNotFailed().doesNotHaveBean(QuestionService.class));
    }

    @Test
    void bothCapabilitiesWireTheRealOrchestratorWithoutCallingEitherCollaborator() {
        runner.withPropertyValues(
                        "secondbrain.questions.enabled=true", "secondbrain.embeddings.enabled=true")
                .withBean(SearchService.class, () -> search)
                .withBean(ChatProvider.class, () -> chat)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(QuestionService.class)
                                    .hasSingleBean(QuestionAnswerService.class);
                            var answer =
                                    context.getBean(QuestionAnswerService.class)
                                            .answer(
                                                    new SearchQuery(
                                                            "Synthetic note",
                                                            SearchScope.selected(Set.of())));
                            assertThat(answer.citations()).isEmpty();
                            assertThat(answer.outcome())
                                    .isEqualTo(
                                            QuestionAnswerService.Outcome.INSUFFICIENT_INFORMATION);
                        });
        verifyNoInteractions(search, chat);
    }

    @Test
    void fakeProviderReceivesRealQuestionInstrumentationWhenAMeterRegistryIsAvailable() {
        var meters = new SimpleMeterRegistry();
        try {
            runner.withPropertyValues(
                            "secondbrain.questions.enabled=true",
                            "secondbrain.embeddings.enabled=true")
                    .withBean(SearchService.class, () -> search)
                    .withBean(ChatProvider.class, () -> chat)
                    .withBean(MeterRegistry.class, () -> meters)
                    .run(
                            context -> {
                                assertThat(context)
                                        .hasNotFailed()
                                        .hasSingleBean(QuestionTelemetry.class);
                                context.getBean(QuestionService.class)
                                        .generate(
                                                new SearchQuery(
                                                        "Synthetic note",
                                                        SearchScope.selected(Set.of())));
                                assertThat(
                                                meters.get("secondbrain.question.requests")
                                                        .tag("outcome", "empty_scope")
                                                        .counter()
                                                        .count())
                                        .isEqualTo(1);
                                assertThat(
                                                meters.get("secondbrain.chat.requests")
                                                        .tag("outcome", "candidate_answer")
                                                        .counter()
                                                        .count())
                                        .isZero();
                            });
        } finally {
            meters.close();
        }
        verifyNoInteractions(search, chat);
    }
}
