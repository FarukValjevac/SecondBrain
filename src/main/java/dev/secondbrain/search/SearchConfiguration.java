package dev.secondbrain.search;

import dev.secondbrain.embeddings.EmbeddingProvider;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Reuses the one configured provider; construction performs no inference. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "secondbrain.embeddings.enabled", havingValue = "true")
@EnableConfigurationProperties(SearchProperties.class)
class SearchConfiguration {
    @Bean
    SearchTelemetry searchTelemetry(
            ObjectProvider<MeterRegistry> meters,
            ObjectProvider<ObservationRegistry> observations) {
        var registry = meters.getIfAvailable();
        return registry == null
                ? SearchTelemetry.disabled()
                : new SearchTelemetry(
                        registry, observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    @Bean
    JdbcPassageSearch jdbcPassageSearch(JdbcClient jdbc, PlatformTransactionManager transactions) {
        return new JdbcPassageSearch(jdbc, transactions);
    }

    @Bean
    SearchService searchService(
            QueryEmbedding embedding,
            JdbcPassageSearch passages,
            SearchProperties properties,
            SearchTelemetry telemetry) {
        return new SearchService(embedding, passages, properties, telemetry);
    }

    @Bean
    QueryEmbedding queryEmbedding(EmbeddingProvider provider) {
        return new QueryEmbedding(provider);
    }
}
