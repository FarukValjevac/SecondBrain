package dev.secondbrain.config;

import dev.secondbrain.embeddings.CloudflareEmbeddingProvider;
import dev.secondbrain.embeddings.CloudflareEmbeddingSettings;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "secondbrain.embeddings.enabled", havingValue = "true")
@ConditionalOnMissingBean(EmbeddingProvider.class)
class EmbeddingConfiguration {
    @Bean(destroyMethod = "close")
    CloudflareEmbeddingProvider cloudflareEmbeddingProvider(
            CloudflareEmbeddingSettings settings, ObjectProvider<Clock> clocks) {
        return new CloudflareEmbeddingProvider(settings, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    CloudflareEmbeddingSettings cloudflareEmbeddingSettings(Environment environment) {
        // Read secrets directly rather than putting credential values in binding-error diagnostics.
        return new CloudflareEmbeddingSettings(
                environment.getProperty("CLOUDFLARE_ACCOUNT_ID", ""),
                environment.getProperty("CLOUDFLARE_API_TOKEN", ""),
                new EmbeddingModel(
                        environment.getProperty("secondbrain.embeddings.model", ""),
                        integer(environment, "dimensions", "0")),
                duration(environment, "connect-timeout", "2s"),
                duration(environment, "request-timeout", "30s"),
                integer(environment, "batch-size", "8"));
    }

    private static int integer(Environment environment, String property, String fallback) {
        try {
            return Integer.parseInt(
                    environment.getProperty("secondbrain.embeddings." + property, fallback));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Embedding numeric configuration is invalid");
        }
    }

    private static Duration duration(Environment environment, String property, String fallback) {
        try {
            return DurationStyle.detectAndParse(
                    environment.getProperty("secondbrain.embeddings." + property, fallback));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Embedding timeout configuration is invalid");
        }
    }
}
