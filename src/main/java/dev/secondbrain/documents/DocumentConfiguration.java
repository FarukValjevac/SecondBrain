package dev.secondbrain.documents;

import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventPublisher;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "secondbrain.events.enabled",
        havingValue = "true",
        matchIfMissing = true)
class DocumentConfiguration {
    @Bean
    JdbcDocumentRepository jdbcDocumentRepository(JdbcClient jdbc) {
        return new JdbcDocumentRepository(jdbc);
    }

    @Bean
    EventEnvelope.Type documentAcceptedEventType() {
        return DocumentAcceptedEvent.TYPE;
    }

    @Bean
    DocumentAcceptanceService documentAcceptanceService(
            DocumentUploadPolicy uploadPolicy,
            OriginalFileStore originalFileStore,
            JdbcDocumentRepository documentRepository,
            EventPublisher eventPublisher,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        return new DocumentAcceptanceService(
                uploadPolicy,
                originalFileStore,
                documentRepository,
                eventPublisher,
                new TransactionTemplate(transactionManager),
                clock);
    }
}
