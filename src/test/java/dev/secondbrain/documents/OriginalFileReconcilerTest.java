package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;

@ExtendWith(OutputCaptureExtension.class)
class OriginalFileReconcilerTest {
    private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofHours(2));
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @TempDir Path directory;
    private OriginalFileStore originals;
    private JdbcDocumentRepository documents;

    @BeforeEach
    void setUp() {
        originals =
                new OriginalFileStore(
                        directory.toString(), new DocumentUploadPolicy(DataSize.ofKilobytes(1)));
        documents = mock(JdbcDocumentRepository.class);
    }

    @Test
    void removesOnlyOldUnownedRegularOriginalsAndIsSafeToRepeat() throws IOException {
        var ownedId = UUID.randomUUID();
        var owned = file(ownedId, OLD);
        var abandoned = file(UUID.randomUUID(), OLD);
        var recent = file(UUID.randomUUID(), NOW);
        var boundary = file(UUID.randomUUID(), NOW.minus(Duration.ofHours(1)));
        var unrelated = Files.writeString(directory.resolve("private-other.txt"), "private");
        var subdirectory = Files.createDirectory(path(UUID.randomUUID()));
        Files.setLastModifiedTime(subdirectory, FileTime.from(OLD));
        when(documents.ownsStorageId(ownedId)).thenReturn(true);

        try (var cleanup = reconciler(100)) {
            assertThat(cleanup.reconcileBatch().deleted()).isEqualTo(1);
            assertThat(abandoned).doesNotExist();
            assertThat(owned).exists();
            assertThat(recent).exists();
            assertThat(boundary).exists();
            assertThat(unrelated).exists();
            assertThat(subdirectory).isDirectory();
            assertThat(cleanup.reconcileBatch().deleted()).isZero();
        }
    }

    @Test
    void doesNotFollowLinksOutsideThePrivateDirectory() throws IOException {
        var external = Files.writeString(directory.resolve("external.txt"), "private sentinel");
        var link = Files.createSymbolicLink(path(UUID.randomUUID()), external);
        Files.setLastModifiedTime(external, FileTime.from(OLD));

        try (var cleanup = reconciler(100)) {
            assertThat(cleanup.reconcileBatch().deleted()).isZero();
            assertThat(link).isSymbolicLink();
            assertThat(Files.readString(external)).isEqualTo("private sentinel");
        }
        verifyNoInteractions(documents);
    }

    @Test
    void keepsItsPlaceSoRetainedFilesDoNotStarveLaterOrphans() throws IOException {
        var abandoned = new ArrayList<Path>();
        for (int index = 0; index < 5; index++) {
            var ownedId = UUID.randomUUID();
            file(ownedId, OLD);
            when(documents.ownsStorageId(ownedId)).thenReturn(true);
            abandoned.add(file(UUID.randomUUID(), OLD));
        }
        try (var cleanup = reconciler(2)) {
            var deleted = 0;
            for (int batch = 0; batch < 10; batch++) {
                var report = cleanup.reconcileBatch();
                assertThat(report.inspected()).isBetween(0, 2);
                deleted += report.deleted();
            }
            assertThat(deleted).isEqualTo(5);
            for (var path : abandoned) {
                assertThat(path).doesNotExist();
            }
        }
    }

    @Test
    void protectsAnUploadEvenAfterItsBytesHaveBeenWritten() throws IOException {
        var content = "active upload".getBytes(StandardCharsets.UTF_8);
        try (var staged = originals.stage(new ByteArrayInputStream(content));
                var cleanup = reconciler(100)) {
            var active = path(staged.original().storageId());
            Files.setLastModifiedTime(active, FileTime.from(OLD));

            assertThat(cleanup.reconcileBatch().busy()).isEqualTo(1);
            assertThat(Files.readAllBytes(active)).containsExactly(content);
            verifyNoInteractions(documents);
        }
        // Simulate a process stopping before the database commit: its protection is gone.
        var restartedStore =
                new OriginalFileStore(
                        directory.toString(), new DocumentUploadPolicy(DataSize.ofKilobytes(1)));
        try (var restarted =
                new OriginalFileReconciler(restartedStore, documents, settings(100), CLOCK)) {
            assertThat(restarted.reconcileBatch().deleted()).isEqualTo(1);
        }
    }

    @Test
    void databaseFailureRetainsTheFileAndScheduledRetryOmitsPrivateDetails(CapturedOutput output)
            throws IOException {
        var abandoned = file(UUID.randomUUID(), OLD);
        doThrow(new DataAccessResourceFailureException("sentinel-private-db-password"))
                .when(documents)
                .ownsStorageId(any());
        try (var cleanup = reconciler(100)) {
            cleanup.cleanup();
            assertThat(abandoned).exists();
            doReturn(false).when(documents).ownsStorageId(any());
            cleanup.cleanup();
            assertThat(abandoned).doesNotExist();
        }
        assertThat(output.getOut())
                .contains(
                        "Original file cleanup will retry", "Original file cleanup batch completed")
                .doesNotContain("sentinel-private", directory.toString(), abandoned.toString());
    }

    @Test
    void failedStagingReleasesProtectionForFutureCleanup() throws IOException {
        assertThatThrownBy(() -> originals.stage(new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(UploadRejectedException.class);
        file(UUID.randomUUID(), OLD);
        try (var cleanup = reconciler(100)) {
            assertThat(cleanup.reconcileBatch().deleted()).isEqualTo(1);
        }
    }

    @Test
    void closingAnUnfinishedScanPreventsMoreCleanup() throws IOException {
        file(UUID.randomUUID(), OLD);
        file(UUID.randomUUID(), OLD);
        var cleanup = reconciler(1);
        try (cleanup) {
            assertThat(cleanup.reconcileBatch().deleted()).isEqualTo(1);
        }
        assertThat(cleanup.reconcileBatch().inspected()).isZero();
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    private OriginalFileReconciler reconciler(int batchSize) {
        return new OriginalFileReconciler(originals, documents, settings(batchSize), CLOCK);
    }

    private static OriginalCleanupProperties settings(int batchSize) {
        return new OriginalCleanupProperties(Duration.ofHours(1), Duration.ofMinutes(1), batchSize);
    }

    private Path file(UUID id, Instant modifiedAt) throws IOException {
        var path = Files.writeString(path(id), "private original");
        Files.setLastModifiedTime(path, FileTime.from(modifiedAt));
        return path;
    }

    private Path path(UUID id) {
        return directory.resolve(id + ".original");
    }
}
