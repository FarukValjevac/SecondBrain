package dev.secondbrain.documents;

import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventPublisher;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
    DocumentIndexingService documentIndexingService(
            JdbcIndexingRepository indexes,
            EventPublisher events,
            PlatformTransactionManager transactions,
            Clock clock,
            DocumentTelemetry telemetry) {
        return new DocumentIndexingService(indexes, events, transactions, clock, telemetry);
    }

    @Bean
    @ConditionalOnProperty(
            name = "secondbrain.documents.indexing.enabled",
            havingValue = "true",
            matchIfMissing = true)
    DocumentIndexingHandler documentIndexingHandler(DocumentIndexingService indexing) {
        return new DocumentIndexingHandler(indexing);
    }

    @Bean
    @ConditionalOnProperty(
            name = {
                "secondbrain.documents.indexing.enabled",
                "secondbrain.documents.indexing.passages.enabled"
            },
            havingValue = "true",
            matchIfMissing = true)
    DocumentPassageHandler documentPassageHandler(
            JdbcIndexingRepository indexes,
            DocumentPassageHandler.Stage stage,
            PlatformTransactionManager transactions) {
        return new DocumentPassageHandler(indexes, stage, transactions);
    }

    @Bean
    @ConditionalOnMissingBean(DocumentPassageHandler.Stage.class)
    @ConditionalOnProperty(
            name = {
                "secondbrain.documents.indexing.enabled",
                "secondbrain.documents.indexing.passages.enabled"
            },
            havingValue = "true",
            matchIfMissing = true)
    DocumentPassagePreparation documentPassagePreparation(
            JdbcExtractedTextRepository texts,
            JdbcPassageRepository passages,
            EventPublisher events,
            Clock clock) {
        return new DocumentPassagePreparation(
                texts, new PassageSplitter(), passages, events, clock);
    }

    @Bean
    EventEnvelope.Type passagesPreparedEventType() {
        // Retain completed passage facts for the embedding consumer introduced in US-03-03.
        return PassagesPreparedEvent.TYPE;
    }

    @Bean
    @ConditionalOnProperty(
            name = {
                "secondbrain.documents.indexing.enabled",
                "secondbrain.documents.indexing.reconcile-enabled"
            },
            havingValue = "true",
            matchIfMissing = true)
    DocumentIndexingReconciliation documentIndexingReconciliation(
            JdbcIndexingRepository indexes,
            DocumentIndexingService indexing,
            EventPublisher events,
            ObjectProvider<DocumentPassageHandler> passages) {
        return new DocumentIndexingReconciliation(indexes, indexing, events, passages);
    }

    @Bean
    EventEnvelope.Type reindexRequestedEventType() {
        // Remains known while passage consumption is paused.
        return ReindexRequestedEvent.TYPE;
    }

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
    @ConditionalOnProperty(
            name = "secondbrain.documents.deletion.enabled",
            havingValue = "true",
            matchIfMissing = true)
    DocumentDeletionHandler documentDeletionHandler(
            JdbcDocumentRepository documents,
            OriginalFileStore originals,
            EventPublisher events,
            PlatformTransactionManager transactions) {
        return new DocumentDeletionHandler(documents, originals, events, transactions);
    }

    @Bean
    @ConditionalOnProperty(
            name = {
                "secondbrain.documents.deletion.enabled",
                "secondbrain.documents.deletion.reconcile-enabled"
            },
            havingValue = "true",
            matchIfMissing = true)
    DocumentDeletionReconciliation documentDeletionReconciliation(EventPublisher events) {
        return new DocumentDeletionReconciliation(events);
    }

    @Bean
    DocumentDeletionService documentDeletionService(
            JdbcDocumentRepository documents,
            EventPublisher events,
            PlatformTransactionManager transactions,
            Clock clock,
            DocumentTelemetry telemetry) {
        return new DocumentDeletionService(documents, events, transactions, clock, telemetry);
    }

    @Bean
    EventEnvelope.Type documentDeletionRequestedEventType() {
        // Remains known when cleanup is disabled, preserving requests for later reconciliation.
        return DocumentDeletionRequestedEvent.TYPE;
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
    DocumentCleanupBacklogMetrics documentCleanupBacklogMetrics(
            JdbcDocumentRepository documents, MeterRegistry meters, Clock clock) {
        return new DocumentCleanupBacklogMetrics(documents, meters, clock);
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
    JdbcIndexingRepository jdbcIndexingRepository(JdbcClient jdbc) {
        return new JdbcIndexingRepository(jdbc);
    }

    @Bean
    JdbcPassageRepository jdbcPassageRepository(
            JdbcTemplate jdbc,
            JdbcIndexingRepository indexes,
            PlatformTransactionManager transactions) {
        return new JdbcPassageRepository(jdbc, indexes, transactions);
    }

    @Bean
    DocumentTextExtractionService documentTextExtractionService(
            OriginalFileStore originals, DocumentUploadPolicy uploadPolicy, Clock clock) {
        return new DocumentTextExtractionService(
                originals,
                new Utf8TextExtractor(uploadPolicy.maximumUploadBytes()),
                new PdfTextExtractor(
                        Math.min(
                                uploadPolicy.maximumUploadBytes(),
                                PdfTextExtractor.MAXIMUM_INPUT_BYTES),
                        clock),
                clock);
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
