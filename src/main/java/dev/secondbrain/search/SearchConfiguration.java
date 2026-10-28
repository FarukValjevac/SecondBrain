package dev.secondbrain.search;

import dev.secondbrain.embeddings.EmbeddingProvider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reuses the one configured provider; construction performs no inference. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "secondbrain.embeddings.enabled", havingValue = "true")
class SearchConfiguration {
    @Bean
    QueryEmbedding queryEmbedding(EmbeddingProvider provider) {
        return new QueryEmbedding(provider);
    }
}
