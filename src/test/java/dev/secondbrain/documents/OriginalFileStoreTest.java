package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

class OriginalFileStoreTest {
    private static final byte[] CONTENT =
            "A small private note.".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @TempDir Path temporaryDirectory;

    @Test
    void storesContentUnderGeneratedKeyAndPreservesItAcrossStoreInstances() throws IOException {
        var store = store(temporaryDirectory.resolve("private-library"), CONTENT.length);

        var original = storeAndRelease(store, CONTENT);
        var storedPath =
                temporaryDirectory.resolve("private-library").resolve(original.storageKey());

        assertThat(original.storageId()).isNotNull();
        assertThat(original.storageKey()).isEqualTo(original.storageId() + ".original");
        assertThat(original.sizeInBytes()).isEqualTo(CONTENT.length);
        assertThat(original.sha256()).isEqualTo(sha256(CONTENT));
        assertThat(Files.readAllBytes(storedPath)).containsExactly(CONTENT);
        if (Files.getFileStore(storedPath).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(storedPath.getParent()))
                    .isEqualTo(
                            EnumSet.of(
                                    PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_WRITE,
                                    PosixFilePermission.OWNER_EXECUTE));
            assertThat(Files.getPosixFilePermissions(storedPath))
                    .isEqualTo(
                            EnumSet.of(
                                    PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_WRITE));
        }
        var reopenedStore = store(temporaryDirectory.resolve("private-library"), CONTENT.length);
        assertThat(Files.isRegularFile(storedPath)).isTrue();
        reopenedStore.delete(original);
        assertThat(Files.exists(storedPath)).isFalse();
    }

    @Test
    void givesIdenticalUploadsDifferentKeysAndDoesNotOverwrite() throws IOException {
        var store = store(temporaryDirectory, CONTENT.length);

        var first = storeAndRelease(store, CONTENT);
        var second = storeAndRelease(store, CONTENT);

        assertThat(first.storageId()).isNotEqualTo(second.storageId());
        assertThat(Files.readAllBytes(temporaryDirectory.resolve(first.storageKey())))
                .containsExactly(CONTENT);
        assertThat(Files.readAllBytes(temporaryDirectory.resolve(second.storageKey())))
                .containsExactly(CONTENT);
    }

    @Test
    void rejectsEmptyAndOversizedContentAndRemovesPartialFiles() throws IOException {
        var store = store(temporaryDirectory, 4);

        assertThatThrownBy(() -> store.stage(new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(UploadRejectedException.class);
        assertThatThrownBy(() -> store.stage(new ByteArrayInputStream(new byte[5])))
                .isInstanceOf(UploadRejectedException.class);

        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void removesOriginalWhenCompensatingForRejectedDatabaseCommit() throws IOException {
        var store = store(temporaryDirectory, CONTENT.length);
        var original = storeAndRelease(store, CONTENT);

        store.delete(original);
        store.delete(original);

        assertThat(Files.exists(temporaryDirectory.resolve(original.storageKey()))).isFalse();
    }

    @Test
    void removesPartialFileWhenSourceReadFails() {
        var store = store(temporaryDirectory, CONTENT.length);
        var failingInput =
                new InputStream() {
                    private boolean suppliedContent;

                    @Override
                    public int read() throws IOException {
                        throw new IOException("private source failure");
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        if (suppliedContent) {
                            throw new IOException("private source failure");
                        }
                        System.arraycopy(CONTENT, 0, buffer, offset, CONTENT.length);
                        suppliedContent = true;
                        return CONTENT.length;
                    }
                };

        assertThatThrownBy(() -> store.stage(failingInput))
                .isInstanceOf(IOException.class)
                .hasMessage("private source failure");
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files).isEmpty();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void removesInterruptedWritePreservesInterruptAndReleasesCleanupProtection() throws Exception {
        var store = store(temporaryDirectory, CONTENT.length);
        var waitingForMoreInput = new CountDownLatch(1);
        var keepReading = new CountDownLatch(1);
        var input =
                new InputStream() {
                    private boolean suppliedContent;

                    @Override
                    public int read() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        if (!suppliedContent) {
                            System.arraycopy(CONTENT, 0, buffer, offset, CONTENT.length);
                            suppliedContent = true;
                            return CONTENT.length;
                        }
                        waitingForMoreInput.countDown();
                        try {
                            if (!keepReading.await(10, TimeUnit.SECONDS)) {
                                throw new IOException("Test input timed out");
                            }
                            return -1;
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            var failure = new InterruptedIOException("Test input interrupted");
                            failure.initCause(interrupted);
                            throw failure;
                        }
                    }
                };
        var result =
                new FutureTask<Boolean>(
                        () -> {
                            assertThatThrownBy(() -> store.stage(input))
                                    .isInstanceOf(InterruptedIOException.class);
                            return Thread.currentThread().isInterrupted();
                        });
        var writer = new Thread(result, "interrupted-upload-test");
        writer.start();
        try {
            assertThat(waitingForMoreInput.await(10, TimeUnit.SECONDS)).isTrue();
            try (var files = Files.list(temporaryDirectory)) {
                var paths = files.toList();
                assertThat(paths).hasSize(1);
                assertThat(Files.readAllBytes(paths.getFirst())).containsExactly(CONTENT);
            }

            writer.interrupt();
            assertThat(result.get(10, TimeUnit.SECONDS)).isTrue();
            try (var files = Files.list(temporaryDirectory)) {
                assertThat(files).isEmpty();
            }

            // A subsequent upload and exclusive cleanup must both work after cancellation.
            var retry = storeAndRelease(store, CONTENT);
            assertThat(store.deleteIfAbandoned(retry.storageId(), Instant.MAX, id -> false))
                    .isEqualTo(OriginalFileStore.CleanupResult.DELETED);
        } finally {
            writer.interrupt();
            keepReading.countDown();
            writer.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertThat(writer.isAlive()).isFalse();
    }

    @Test
    void rejectsExistingPosixStorageDirectoryWithSharedPermissions() throws IOException {
        var directory = temporaryDirectory.resolve("shared-library");
        Files.createDirectory(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(
                    directory,
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE));

            assertThatThrownBy(
                            () ->
                                    store(directory, CONTENT.length)
                                            .stage(new ByteArrayInputStream(CONTENT)))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Document storage directory permissions must be private");
            try (var files = Files.list(directory)) {
                assertThat(files).isEmpty();
            }
        }
    }

    private static OriginalFileStore.StoredOriginal storeAndRelease(
            OriginalFileStore store, byte[] content) throws IOException {
        try (var staged = store.stage(new ByteArrayInputStream(content))) {
            return staged.original();
        }
    }

    private static OriginalFileStore store(Path path, long maximumBytes) {
        return new OriginalFileStore(
                path.toString(), new DocumentUploadPolicy(DataSize.ofBytes(maximumBytes)));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
