package dev.secondbrain.documents;

import dev.secondbrain.embeddings.EmbeddingProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
            @Value("${secondbrain.embeddings.batch-size:8}") int batchSize) {
        return new DocumentEmbeddingHandler(indexes, embeddings, provider, batchSize);
    }
}
