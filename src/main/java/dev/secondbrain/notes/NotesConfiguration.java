package dev.secondbrain.notes;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/** Highlights need the document library's database, so they follow its switch. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "secondbrain.events.enabled",
        havingValue = "true",
        matchIfMissing = true)
class NotesConfiguration {
    @Bean
    HighlightService highlightService(
            JdbcClient jdbc, PlatformTransactionManager transactions, Clock clock) {
        return new HighlightService(jdbc, transactions, clock);
    }
}
