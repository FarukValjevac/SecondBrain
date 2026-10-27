package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.secondbrain.embeddings.EmbeddingModel;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@ExtendWith(OutputCaptureExtension.class)
class IndexReadinessMetricsTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:10Z");
    private final JdbcIndexingRepository indexes = mock(JdbcIndexingRepository.class);
    private final EmbeddingModel model = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
    private final IndexingVersion version = new IndexingVersion(UUID.randomUUID(), 7, 2, NOW);
    private final Clock clock = mock(Clock.class);
    // SQL isolation is tested against PostgreSQL; this manager captures the read-only definition.
    private final PlatformTransactionManager reads = mock(PlatformTransactionManager.class);

    private IndexReadinessMetrics metrics(MeterRegistry meters) {
        when(clock.instant()).thenReturn(NOW);
        when(reads.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new IndexReadinessMetrics(indexes, model, meters, clock, reads);
    }

    @Test
    void sharesOneBoundedCommittedReadAcrossUnlabelledGauges() {
        when(indexes.activeCount(model)).thenReturn(3L);
        var meters = new SimpleMeterRegistry();
        try {
            metrics(meters);
            assertThat(meters.get("secondbrain.index.active").gauge().value()).isEqualTo(3);
            assertThat(meters.get("secondbrain.index.active.available").gauge().value())
                    .isEqualTo(1);
            verify(indexes).activeCount(model);
            verify(reads)
                    .getTransaction(
                            argThat(
                                    def ->
                                            def.isReadOnly()
                                                    && def.getTimeout() == 5
                                                    && def.getPropagationBehavior()
                                                            == TransactionDefinition
                                                                    .PROPAGATION_REQUIRES_NEW));
            assertThat(meters.getMeters())
                    .allSatisfy(m -> assertThat(m.getId().getTags()).isEmpty());
        } finally {
            meters.close();
        }
    }

    @Test
    void cachesFailureAsUnavailableAndRecoversWithoutInventingAnEmptyLibrary(
            CapturedOutput output) {
        when(indexes.activeCount(model))
                .thenThrow(new IllegalStateException("private-query-sentinel"))
                .thenReturn(0L);
        var meters = new SimpleMeterRegistry();
        try {
            metrics(meters);
            assertThat(meters.get("secondbrain.index.active").gauge().value()).isNaN();
            assertThat(meters.get("secondbrain.index.active.available").gauge().value()).isZero();
            verify(indexes).activeCount(model);
            when(clock.instant()).thenReturn(NOW.plusSeconds(5));
            assertThat(meters.get("secondbrain.index.active.available").gauge().value())
                    .isEqualTo(1);
            assertThat(meters.get("secondbrain.index.active").gauge().value()).isZero();
            verify(indexes, times(2)).activeCount(model);
        } finally {
            meters.close();
        }
        assertThat(output.getAll()).doesNotContain("private-query-sentinel");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recordsUploadToReadyOnlyAfterCommitAndNeverAfterRollback(boolean rollback) {
        when(indexes.activeAcceptedAt(version)).thenReturn(Optional.of(NOW.minusSeconds(10)));
        var meters = new SimpleMeterRegistry();
        try {
            var metrics = metrics(meters);
            new TransactionTemplate(new DocumentIndexingServiceTest.CallbackTransactions())
                    .executeWithoutResult(
                            status -> {
                                metrics.afterActivation(version);
                                assertThat(
                                                meters.get("secondbrain.index.ready.duration")
                                                        .timer()
                                                        .count())
                                        .isZero();
                                verify(indexes, never()).activeAcceptedAt(any());
                                if (rollback) status.setRollbackOnly();
                            });
            var timer = meters.get("secondbrain.index.ready.duration").timer();
            assertThat(timer.count()).isEqualTo(rollback ? 0 : 1);
            assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(rollback ? 0 : 10);
            assertThat(timer.getId().getTags()).isEmpty();
        } finally {
            meters.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void omitsSamplesForRemovedOrFutureDatedDocuments(boolean future) {
        when(indexes.activeAcceptedAt(version))
                .thenReturn(future ? Optional.of(NOW.plusSeconds(1)) : Optional.empty());
        var meters = new SimpleMeterRegistry();
        try {
            var metrics = metrics(meters);
            new TransactionTemplate(new DocumentIndexingServiceTest.CallbackTransactions())
                    .executeWithoutResult(status -> metrics.afterActivation(version));
            assertThat(meters.get("secondbrain.index.ready.duration").timer().count()).isZero();
        } finally {
            meters.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void brokenRegistrationReadOrTimerCannotChangeCommitOrExposePrivateFailures(
            boolean readFails, CapturedOutput output) {
        if (readFails)
            when(indexes.activeAcceptedAt(version))
                    .thenThrow(new IllegalStateException("private-database-sentinel"));
        else when(indexes.activeAcceptedAt(version)).thenReturn(Optional.of(NOW.minusSeconds(10)));
        var meters = new SimpleMeterRegistry();
        try {
            meters.config()
                    .meterFilter(
                            new MeterFilter() {
                                @Override
                                public Meter.Id map(Meter.Id id) {
                                    throw new IllegalStateException("private-registry-sentinel");
                                }
                            });
            var metrics = metrics(meters);
            assertThatCode(
                            () ->
                                    new TransactionTemplate(
                                                    new DocumentIndexingServiceTest
                                                            .CallbackTransactions())
                                            .executeWithoutResult(
                                                    status -> metrics.afterActivation(version)))
                    .doesNotThrowAnyException();
            verify(indexes).activeAcceptedAt(version);
        } finally {
            meters.close();
        }
        assertThat(output.getAll())
                .contains("Index readiness telemetry unavailable")
                .doesNotContain(
                        "private-database-sentinel",
                        "private-registry-sentinel",
                        version.documentId().toString());
    }
}
