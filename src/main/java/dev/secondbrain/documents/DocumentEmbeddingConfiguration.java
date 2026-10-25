package dev.secondbrain.documents;

import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.events.EventPublisher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/** Separate opt-in keeps ordinary local startup independent of inference credentials. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "secondbrain.embeddings.enabled", havingValue = "true")
class DocumentEmbeddingConfiguration {
    @Bean
    @ConditionalOnProperty(
            name = {"secondbrain.events.enabled", "secondbrain.documents.indexing.enabled"},
            havingValue = "true",
            matchIfMissing = true)
    DocumentEmbeddingHandler documentEmbeddingHandler(
            JdbcIndexingRepository indexes,
            JdbcEmbeddingRepository embeddings,
            EmbeddingProvider provider,
            DocumentIndexPublication publication,
            @Value("${secondbrain.embeddings.batch-size:8}") int batchSize) {
        return new DocumentEmbeddingHandler(indexes, embeddings, provider, batchSize, publication);
    }

    @Bean
    @ConditionalOnProperty(
            name = {"secondbrain.events.enabled", "secondbrain.documents.indexing.enabled"},
            havingValue = "true",
            matchIfMissing = true)
    DocumentIndexPublication documentIndexPublication(
            JdbcIndexingRepository indexes,
            EventPublisher events,
            EmbeddingProvider provider,
            Clock clock,
            PlatformTransactionManager transactions) {
        return new DocumentIndexPublication(indexes, events, provider.model(), clock, transactions);
    }
}
