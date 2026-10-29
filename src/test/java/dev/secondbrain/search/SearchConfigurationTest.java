package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

class SearchConfigurationTest {
    @Test
    void bindsTheExternalRelevanceThreshold() {
        new ApplicationContextRunner()
                .withUserConfiguration(ThresholdConfiguration.class)
                .withPropertyValues("secondbrain.search.minimum-score=0.65")
                .run(
                        context ->
                                assertThat(context.getBean(SearchProperties.class).minimumScore())
                                        .isEqualTo(0.65));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SearchProperties.class)
    static class ThresholdConfiguration {}

    @ParameterizedTest
    @ValueSource(strings = {"", "secondbrain.embeddings.enabled=false"})
    void defaultAndDisabledStartupNeedNoProviderOrCredentials(String property) {
        var runner =
                new ApplicationContextRunner().withUserConfiguration(SearchConfiguration.class);
        if (!property.isEmpty()) runner = runner.withPropertyValues(property);
        runner.run(context -> assertThat(context).doesNotHaveBean(QueryEmbedding.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "secondbrain.events.enabled=false",
                "secondbrain.documents.indexing.enabled=false"
            })
    void reusesTheProviderWithoutStartupInferenceOrBackgroundProcessingDependency(String property) {
        var provider = mock(EmbeddingProvider.class);
        var model = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
        when(provider.model()).thenReturn(model);
        var runner =
                new ApplicationContextRunner()
                        .withUserConfiguration(SearchConfiguration.class)
                        .withPropertyValues("secondbrain.embeddings.enabled=true")
                        .withBean(EmbeddingProvider.class, () -> provider)
                        .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                        .withBean(
                                PlatformTransactionManager.class,
                                () -> mock(PlatformTransactionManager.class));
        if (!property.isEmpty()) runner = runner.withPropertyValues(property);
        runner.run(
                context -> {
                    assertThat(context)
                            .hasSingleBean(QueryEmbedding.class)
                            .hasSingleBean(EmbeddingProvider.class)
                            .hasSingleBean(SearchService.class);
                    assertThat(context.getBean(SearchProperties.class).minimumScore())
                            .isEqualTo(0.3);
                    assertThat(context.getBean(QueryEmbedding.class).model()).isEqualTo(model);
                    try {
                        verify(provider, never()).embed(any());
                    } catch (InterruptedException impossible) {
                        throw new AssertionError(impossible);
                    }
                });
    }
}
