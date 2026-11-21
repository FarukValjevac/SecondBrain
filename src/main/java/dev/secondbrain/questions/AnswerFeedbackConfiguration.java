package dev.secondbrain.questions;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.Optional;

/** Feedback exists only alongside answers; it is stored locally and never sent anywhere. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"secondbrain.questions.enabled", "secondbrain.embeddings.enabled"},
        havingValue = "true")
class AnswerFeedbackConfiguration {
    @Bean
    AnswerFeedbackService answerFeedbackService(
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            ObjectProvider<Clock> clocks,
            ObjectProvider<MeterRegistry> meters) {
        return new AnswerFeedbackService(
                new JdbcAnswerFeedbackRepository(jdbc, transactions),
                clocks.getIfAvailable(Clock::systemUTC),
                Optional.ofNullable(meters.getIfAvailable()));
    }
}
