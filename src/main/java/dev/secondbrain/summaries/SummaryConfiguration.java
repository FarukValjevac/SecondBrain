package dev.secondbrain.summaries;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.events.EventPublisher;
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

/** Off by default: every summary is one chat request against the shared free allowance. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {
            "secondbrain.summaries.enabled",
            "secondbrain.questions.enabled",
            "secondbrain.embeddings.enabled"
        },
        havingValue = "true")
class SummaryConfiguration {
    @Bean
    JdbcSummaryRepository summaryRepository(
            JdbcClient jdbc, PlatformTransactionManager transactions) {
        return new JdbcSummaryRepository(jdbc, transactions);
    }

    @Bean
    SummaryGenerator summaryGenerator(
            SearchService search,
            ChatProvider chat,
            JdbcSummaryRepository summaries,
            ObjectProvider<Clock> clocks,
            ObjectProvider<MeterRegistry> meters) {
        return new SummaryGenerator(
                search,
                chat,
                summaries,
                clocks.getIfAvailable(Clock::systemUTC),
                Optional.ofNullable(meters.getIfAvailable()));
    }

    @Bean
    SummaryHandler summaryAfterPublication(SummaryGenerator generator) {
        return SummaryHandler.afterPublication(generator);
    }

    @Bean
    SummaryHandler summaryOnRequest(SummaryGenerator generator) {
        return SummaryHandler.onRequest(generator);
    }

    @Bean
    SummaryService summaryService(
            JdbcSummaryRepository summaries,
            DocumentInspectionService documents,
            EventPublisher events,
            PlatformTransactionManager transactions,
            ObjectProvider<Clock> clocks) {
        return new SummaryService(
                summaries,
                documents,
                events,
                transactions,
                clocks.getIfAvailable(Clock::systemUTC));
    }
}
