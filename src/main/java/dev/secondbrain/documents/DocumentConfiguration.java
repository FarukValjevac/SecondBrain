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
    @ConditionalOnProperty(
            name = "secondbrain.documents.extraction.enabled",
            havingValue = "true",
            matchIfMissing = true)
    DocumentExtractionHandler documentExtractionHandler(
            JdbcDocumentRepository documents,
            DocumentTextExtractionService extraction,
            DocumentExtractionCompletion completion) {
        return new DocumentExtractionHandler(documents, extraction, completion);
    }

    @Bean
    @ConditionalOnProperty(
            name = {
                "secondbrain.documents.extraction.enabled",
                "secondbrain.documents.extraction.reconcile-enabled"
            },
            havingValue = "true",
            matchIfMissing = true)
    DocumentExtractionReconciliation documentExtractionReconciliation(EventPublisher events) {
        return new DocumentExtractionReconciliation(events);
    }

    @Bean
    DocumentInspectionService documentInspectionService(
            JdbcDocumentRepository documents,
            JdbcExtractedTextRepository texts,
            EventPublisher events,
            PlatformTransactionManager transactions,
            Clock clock) {
        return new DocumentInspectionService(documents, texts, events, transactions, clock);
    }

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
    DocumentTextExtractionService documentTextExtractionService(
            OriginalFileStore originals, DocumentUploadPolicy uploadPolicy, Clock clock) {
        return new DocumentTextExtractionService(
                originals, new Utf8TextExtractor(uploadPolicy.maximumUploadBytes()), clock);
    }

    @Bean
    EventEnvelope.Type documentAcceptedEventType() {
        return DocumentAcceptedEvent.TYPE;
    }

    @Bean
    EventEnvelope.Type textExtractedEventType() {
        return TextExtractedEvent.TYPE;
    }

    @Bean
    DocumentExtractionCompletion documentExtractionCompletion(
            JdbcDocumentRepository documents,
            JdbcExtractedTextRepository texts,
            EventPublisher events,
            Clock clock,
            PlatformTransactionManager transactions) {
        return new DocumentExtractionCompletion(documents, texts, events, clock, transactions);
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
