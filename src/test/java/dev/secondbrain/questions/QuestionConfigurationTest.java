package dev.secondbrain.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchScope;
import dev.secondbrain.search.SearchService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;

class QuestionConfigurationTest {
    private final SearchService search = mock(SearchService.class);
    private final ChatProvider chat = mock(ChatProvider.class);
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(QuestionConfiguration.class);

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
                            assertThat(context).hasNotFailed().hasSingleBean(QuestionService.class);
                            var candidate =
                                    context.getBean(QuestionService.class)
                                            .generate(
                                                    new SearchQuery(
                                                            "Synthetic note",
                                                            SearchScope.selected(Set.of())));
                            assertThat(candidate.context()).isEmpty();
                            assertThat(candidate.reply().outcome())
                                    .isEqualTo(ChatProvider.Outcome.INSUFFICIENT_INFORMATION);
                        });
        verifyNoInteractions(search, chat);
    }
}
