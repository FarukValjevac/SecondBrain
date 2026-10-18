package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

@ExtendWith(OutputCaptureExtension.class)
class DocumentCleanupBacklogMetricsTest {
    @Test
    void sharesOneCachedProjectionAcrossBoundedPrivateSafeGauges() {
        var documents = mock(JdbcDocumentRepository.class);
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        when(documents.cleanupBacklog(any()))
                .thenReturn(new JdbcDocumentRepository.CleanupBacklog(2, 1, 30));
        var meters = new SimpleMeterRegistry();
        try {
            new DocumentCleanupBacklogMetrics(documents, meters, clock);
            assertThat(gauge(meters, "secondbrain.document.deletion.backlog", "pending"))
                    .isEqualTo(2);
            assertThat(gauge(meters, "secondbrain.document.deletion.backlog", "failed"))
                    .isEqualTo(1);
            assertThat(gauge(meters, "secondbrain.document.deletion.backlog.oldest", null))
                    .isEqualTo(30);
            assertThat(gauge(meters, "secondbrain.document.deletion.backlog.available", null))
                    .isEqualTo(1);
            assertThat(
                            meters.get("secondbrain.document.deletion.backlog.oldest")
                                    .gauge()
                                    .getId()
                                    .getBaseUnit())
                    .isEqualTo("seconds");
            assertThat(meters.find("secondbrain.document.deletion.backlog").gauges()).hasSize(2);
            for (var meter : meters.getMeters()) {
                assertThat(meter.getId().getTags())
                        .allSatisfy(
                                tag -> {
                                    assertThat(tag.getKey()).isEqualTo("state");
                                    assertThat(tag.getValue()).isIn(Set.of("pending", "failed"));
                                });
            }
            verify(documents).cleanupBacklog(Instant.EPOCH);
        } finally {
            meters.close();
        }
    }

    @Test
    void cachesQueryFailureAsUnavailableAndRecoversWithoutReportingAnInventedZero(
            CapturedOutput output) {
        var documents = mock(JdbcDocumentRepository.class);
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        when(documents.cleanupBacklog(any()))
                .thenThrow(new IllegalStateException("sentinel-private-error"))
                .thenReturn(new JdbcDocumentRepository.CleanupBacklog(0, 0, 0));
        var meters = new SimpleMeterRegistry();
        try {
            new DocumentCleanupBacklogMetrics(documents, meters, clock);
            assertThat(gauge(meters, "secondbrain.document.deletion.backlog", "pending")).isNaN();
            assertThat(gauge(meters, "secondbrain.document.deletion.backlog", "failed")).isNaN();
            assertThat(gauge(meters, "secondbrain.document.deletion.backlog.oldest", null)).isNaN();
            assertThat(gauge(meters, "secondbrain.document.deletion.backlog.available", null))
                    .isZero();
            verify(documents).cleanupBacklog(Instant.EPOCH);
            when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(6));
            assertThat(gauge(meters, "secondbrain.document.deletion.backlog", "pending")).isZero();
            assertThat(gauge(meters, "secondbrain.document.deletion.backlog.oldest", null))
                    .isZero();
            assertThat(gauge(meters, "secondbrain.document.deletion.backlog.available", null))
                    .isEqualTo(1);
            verify(documents, times(2)).cleanupBacklog(any());
        } finally {
            meters.close();
        }
        assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @Test
    void brokenMeterRegistrationCannotPreventStartup(CapturedOutput output) {
        var meters = new SimpleMeterRegistry();
        try {
            meters.config()
                    .meterFilter(
                            new MeterFilter() {
                                @Override
                                public Meter.Id map(Meter.Id id) {
                                    throw new IllegalStateException("sentinel-private-registry");
                                }
                            });
            assertThatCode(
                            () ->
                                    new DocumentCleanupBacklogMetrics(
                                            mock(JdbcDocumentRepository.class),
                                            meters,
                                            Clock.systemUTC()))
                    .doesNotThrowAnyException();
        } finally {
            meters.close();
        }
        assertThat(output.getAll())
                .contains("Cleanup backlog telemetry unavailable")
                .doesNotContain("sentinel-private");
    }

    private static double gauge(SimpleMeterRegistry meters, String name, String state) {
        var search = meters.get(name);
        return (state == null ? search : search.tag("state", state)).gauge().value();
    }
}
