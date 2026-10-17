package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;

@ExtendWith(OutputCaptureExtension.class)
class OriginalStorageHealthIndicatorTest {
    @Test
    void unavailableStorageHasNoPrivateDetailsEvenWhenHealthDetailsAreRequested(
            CapturedOutput output) throws IOException {
        for (var failure :
                new Exception[] {
                    new IOException("sentinel-private-storage-path"),
                    new SecurityException("sentinel-private-permission-details"),
                    new IllegalStateException("sentinel-private-provider-error")
                }) {
            var originals = mock(OriginalFileStore.class);
            doThrow(failure).when(originals).checkReadiness();
            var health = new OriginalStorageHealthIndicator(originals, true).health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).isEmpty();
            assertThat(health.toString()).doesNotContain("sentinel-private");
        }
        assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @Test
    void disabledUploadsDoNotProbeOrRequireOriginalStorage() {
        var originals = mock(OriginalFileStore.class);
        var health = new OriginalStorageHealthIndicator(originals, false).health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).isEmpty();
        verifyNoInteractions(originals);
    }
}
