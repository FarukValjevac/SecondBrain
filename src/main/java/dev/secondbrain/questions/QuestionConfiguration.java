package dev.secondbrain.questions;

import dev.secondbrain.search.SearchService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Both capabilities are needed for orchestration; constructing the service performs no I/O. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"secondbrain.questions.enabled", "secondbrain.embeddings.enabled"},
        havingValue = "true")
class QuestionConfiguration {
    @Bean
    QuestionService questionService(SearchService search, ChatProvider chat) {
        return new QuestionService(search, chat);
    }
}
