package dev.secondbrain.documents;

import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventPublisher;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OriginalCleanupProperties.class)
@ConditionalOnProperty(
        name = "secondbrain.events.enabled",
        havingValue = "true",
        matchIfMissing = true)
class DocumentConfiguration {
    @Bean
    DocumentTelemetry documentTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        return new DocumentTelemetry(meters, observations);
    }

    @Bean
    @ConditionalOnProperty(
            name = "secondbrain.documents.cleanup.enabled",
            havingValue = "true",
            matchIfMissing = true)
    OriginalFileReconciler originalFileReconciler(
            OriginalFileStore originals,
            JdbcDocumentRepository documents,
            OriginalCleanupProperties settings,
            Clock clock,
            DocumentTelemetry telemetry) {
        return new OriginalFileReconciler(originals, documents, settings, clock, telemetry);
    }

    @Bean
    JdbcDocumentRepository jdbcDocumentRepository(JdbcClient jdbc) {
        return new JdbcDocumentRepository(jdbc);
    }

    @Bean
    JdbcExtractedTextRepository jdbcExtractedTextRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new JdbcExtractedTextRepository(jdbc, transactionManager);
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
            Clock clock,
            DocumentTelemetry telemetry) {
        return new DocumentAcceptanceService(
                uploadPolicy,
                originalFileStore,
                documentRepository,
                eventPublisher,
                new TransactionTemplate(transactionManager),
                clock,
                telemetry);
    }
}
