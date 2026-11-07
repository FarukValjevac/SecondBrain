package dev.secondbrain.config;

import dev.secondbrain.questions.ChatProvider;
import dev.secondbrain.questions.CloudflareChatProvider;
import dev.secondbrain.questions.CloudflareChatSettings;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.Duration;

/** Opt-in provider wiring; construction and startup never perform inference. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "secondbrain.questions.enabled", havingValue = "true")
@ConditionalOnMissingBean(ChatProvider.class)
class ChatConfiguration {
    @Bean(destroyMethod = "close")
    CloudflareChatProvider cloudflareChatProvider(
            CloudflareChatSettings settings, ObjectProvider<Clock> clocks) {
        return new CloudflareChatProvider(settings, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    CloudflareChatSettings cloudflareChatSettings(Environment environment) {
        // Read secrets directly, outside value-binding diagnostics that could print their contents.
        return new CloudflareChatSettings(
                environment.getProperty("CLOUDFLARE_ACCOUNT_ID", ""),
                environment.getProperty("CLOUDFLARE_API_TOKEN", ""),
                environment.getProperty("secondbrain.questions.model", ""),
                duration(environment, "connect-timeout", "2s"),
                duration(environment, "request-timeout", "30s"),
                maximumOutputTokens(environment));
    }

    private static int maximumOutputTokens(Environment environment) {
        try {
            return Integer.parseInt(
                    environment.getProperty("secondbrain.questions.max-output-tokens", "512"));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Chat output token configuration is invalid");
        }
    }

    private static Duration duration(Environment environment, String property, String fallback) {
        try {
            return DurationStyle.detectAndParse(
                    environment.getProperty("secondbrain.questions." + property, fallback));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Chat timeout configuration is invalid");
        }
    }
}
