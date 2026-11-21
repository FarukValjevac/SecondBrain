package dev.secondbrain.study;

import dev.secondbrain.questions.ChatProvider;
import dev.secondbrain.search.SearchService;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.Optional;

/** Study tools need questions (for generation) and embeddings (for representative passages). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"secondbrain.questions.enabled", "secondbrain.embeddings.enabled"},
        havingValue = "true")
class StudyConfiguration {
    @Bean
    JdbcFlashcardRepository flashcardRepository(
            JdbcClient jdbc, PlatformTransactionManager transactions) {
        return new JdbcFlashcardRepository(jdbc, transactions);
    }

    @Bean
    FlashcardService flashcardService(
            SearchService search,
            ChatProvider chat,
            JdbcFlashcardRepository cards,
            ObjectProvider<Clock> clocks,
            ObjectProvider<MeterRegistry> meters) {
        return new FlashcardService(
                search,
                chat,
                cards,
                clocks.getIfAvailable(Clock::systemUTC),
                Optional.ofNullable(meters.getIfAvailable()));
    }
}
