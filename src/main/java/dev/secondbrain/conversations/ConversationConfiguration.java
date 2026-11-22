package dev.secondbrain.conversations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/** Conversations need the database, so they follow the document library's switch. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "secondbrain.events.enabled",
        havingValue = "true",
        matchIfMissing = true)
class ConversationConfiguration {
    @Bean
    ConversationService conversationService(
            JdbcClient jdbc, PlatformTransactionManager transactions, Clock clock) {
        return new ConversationService(new JdbcConversationRepository(jdbc), transactions, clock);
    }
}
