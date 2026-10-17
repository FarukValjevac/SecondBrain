package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.secondbrain.events.EventEnvelope;
import dev.secondbrain.events.EventPublisher;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Clock;

class DocumentConfigurationTest {
    @TempDir private Path storageDirectory;

    @Test
    void wiresAcceptanceWhenJdbcAndEventsAreAvailable() {
        configuredContext()
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(DocumentAcceptanceService.class);
                            assertThat(context).hasSingleBean(JdbcDocumentRepository.class);
                            assertThat(context).hasSingleBean(JdbcExtractedTextRepository.class);
                            assertThat(context).hasSingleBean(DocumentTextExtractionService.class);
                            assertThat(context).hasSingleBean(DocumentExtractionCompletion.class);
                            assertThat(context).hasSingleBean(OriginalFileReconciler.class);
                            assertThat(context).hasSingleBean(DocumentTelemetry.class);
                            assertThat(context.getBeansOfType(EventEnvelope.Type.class).values())
                                    .containsExactlyInAnyOrder(
                                            DocumentAcceptedEvent.TYPE, TextExtractedEvent.TYPE);
                        });
    }

    @Test
    void disablesAcceptanceWhenDurablePublicationIsDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(DocumentConfiguration.class)
                .withPropertyValues("secondbrain.events.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(DocumentAcceptanceService.class);
                            assertThat(context).doesNotHaveBean(JdbcDocumentRepository.class);
                            assertThat(context).doesNotHaveBean(JdbcExtractedTextRepository.class);
                            assertThat(context)
                                    .doesNotHaveBean(DocumentTextExtractionService.class);
                            assertThat(context).doesNotHaveBean(DocumentExtractionCompletion.class);
                            assertThat(context).doesNotHaveBean(OriginalFileReconciler.class);
                            assertThat(context).doesNotHaveBean(DocumentTelemetry.class);
                        });
    }

    @Test
    void canDisableCleanupWithoutDisablingUploads() {
        configuredContext()
                .withPropertyValues("secondbrain.documents.cleanup.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(DocumentAcceptanceService.class);
                            assertThat(context).doesNotHaveBean(OriginalFileReconciler.class);
                        });
    }

    @Test
    void rejectsUnboundedOrUnsafeCleanupSettings() {
        for (var value :
                new String[] {
                    "grace-period=0s", "interval=0s", "batch-size=0", "batch-size=1001"
                }) {
            configuredContext()
                    .withPropertyValues("secondbrain.documents.cleanup." + value)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    private ApplicationContextRunner configuredContext() {
        var policy = new DocumentUploadPolicy(DataSize.ofMegabytes(10));
        return new ApplicationContextRunner()
                .withUserConfiguration(DocumentConfiguration.class)
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(EventPublisher.class, () -> mock(EventPublisher.class))
                .withBean(
                        PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(ObservationRegistry.class, ObservationRegistry::create)
                .withBean(DocumentUploadPolicy.class, () -> policy)
                .withBean(
                        OriginalFileStore.class,
                        () -> new OriginalFileStore(storageDirectory.toString(), policy));
    }
}
