package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DocumentEmbeddingConfigurationTest {
    @Test
    void disabledStartupNeedsNeitherCredentialsNorProvider() {
        new ApplicationContextRunner()
                .withUserConfiguration(DocumentEmbeddingConfiguration.class)
                .run(
                        context ->
                                assertThat(context)
                                        .doesNotHaveBean(DocumentEmbeddingHandler.class));
    }

    @Test
    void optInRegistersTheDurableHandlerWithAControlledProvider() {
        configured()
                .run(context -> assertThat(context).hasSingleBean(DocumentEmbeddingHandler.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "secondbrain.events.enabled=false",
                "secondbrain.documents.indexing.enabled=false"
            })
    void respectsDisabledProcessing(String property) {
        configured()
                .withPropertyValues(property)
                .run(
                        context ->
                                assertThat(context)
                                        .doesNotHaveBean(DocumentEmbeddingHandler.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 33})
    void rejectsAnUnboundedBatchSize(int size) {
        configured()
                .withPropertyValues("secondbrain.embeddings.batch-size=" + size)
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner configured() {
        var provider = mock(EmbeddingProvider.class);
        when(provider.model()).thenReturn(new EmbeddingModel("@cf/baai/bge-m3", 1024));
        return new ApplicationContextRunner()
                .withUserConfiguration(DocumentEmbeddingConfiguration.class)
                .withPropertyValues("secondbrain.embeddings.enabled=true")
                .withBean(EmbeddingProvider.class, () -> provider)
                .withBean(JdbcIndexingRepository.class, () -> mock(JdbcIndexingRepository.class))
                .withBean(JdbcEmbeddingRepository.class, () -> mock(JdbcEmbeddingRepository.class))
                .withBean(
                        dev.secondbrain.events.EventPublisher.class,
                        () -> mock(dev.secondbrain.events.EventPublisher.class))
                .withBean(java.time.Clock.class, java.time.Clock::systemUTC)
                .withBean(
                        io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withBean(
                        org.springframework.transaction.PlatformTransactionManager.class,
                        DocumentIndexingServiceTest.CallbackTransactions::new);
    }
}
