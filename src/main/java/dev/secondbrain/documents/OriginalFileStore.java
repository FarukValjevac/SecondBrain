package dev.secondbrain.documents;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;

@Component
final class OriginalFileStore {
    private static final int BUFFER_SIZE = 8192;
    private static final Set<PosixFilePermission> SHARED_DIRECTORY_PERMISSIONS =
            Set.of(
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE);

    private final Path storageDirectory;
    private final DocumentUploadPolicy uploadPolicy;
    // Shared by this application's uploads and cleanup; no per-upload state lives in the store.
    private final ReentrantReadWriteLock access = new ReentrantReadWriteLock();

    OriginalFileStore(
            @Value("${secondbrain.documents.storage-directory}") String storageDirectory,
            DocumentUploadPolicy uploadPolicy) {
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
        this.uploadPolicy = uploadPolicy;
    }

    /**
     * Keep this scope open through metadata/event commit or compensation. Input is caller-owned.
     */
    StagedOriginal stage(InputStream input) throws IOException {
        var protection = access.readLock();
        protection.lock();
        var staged = false;
        try {
            var original = new StagedOriginal(store(input), protection);
            staged = true;
            return original;
        } finally {
            if (!staged) {
                protection.unlock();
            }
        }
    }

    private StoredOriginal store(InputStream input) throws IOException {
        var storageId = UUID.randomUUID();
        var destination = pathFor(storageId);
        createPrivateStorageDirectory();

        var digest = sha256Digest();
        var sizeInBytes = 0L;
        var channel =
                FileChannel.open(
                        destination,
                        Set.<OpenOption>of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        privateFileAttributes());
        try (channel) {
            var bytes = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = input.read(bytes)) != -1) {
                sizeInBytes += bytesRead;
                uploadPolicy.validateSize(sizeInBytes);
                digest.update(bytes, 0, bytesRead);
                var buffer = ByteBuffer.wrap(bytes, 0, bytesRead);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
            uploadPolicy.validateSize(sizeInBytes);
            channel.force(true);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }

        return new StoredOriginal(
                storageId, sizeInBytes, HexFormat.of().formatHex(digest.digest()));
    }

    void delete(StoredOriginal original) throws IOException {
        Files.deleteIfExists(pathFor(original.storageId()));
    }

    Scan openScan() throws IOException {
        createPrivateStorageDirectory();
        return new Scan(Files.newDirectoryStream(storageDirectory));
    }

    CleanupResult deleteIfAbandoned(UUID storageId, Instant cutoff, Predicate<UUID> isOwned)
            throws IOException {
        var protection = access.writeLock();
        if (!protection.tryLock()) {
            return CleanupResult.BUSY;
        }
        try {
            var path = pathFor(storageId);
            BasicFileAttributes attributes;
            try {
                attributes =
                        Files.readAttributes(
                                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                return CleanupResult.RETAINED;
            }
            // Never follow links or infer abandonment from age alone. Database errors propagate.
            if (!attributes.isRegularFile()
                    || !attributes.lastModifiedTime().toInstant().isBefore(cutoff)
                    || isOwned.test(storageId)) {
                return CleanupResult.RETAINED;
            }
            return Files.deleteIfExists(path) ? CleanupResult.DELETED : CleanupResult.RETAINED;
        } finally {
            protection.unlock();
        }
    }

    private Path pathFor(UUID storageId) {
        return storageDirectory.resolve(storageId + ".original");
    }

    private void createPrivateStorageDirectory() throws IOException {
        if (nearestExistingFileStore().supportsFileAttributeView("posix")) {
            Files.createDirectories(storageDirectory, privateDirectoryAttributes());
        } else {
            Files.createDirectories(storageDirectory);
        }
        if (Files.getFileStore(storageDirectory).supportsFileAttributeView("posix")) {
            var permissions = Files.getPosixFilePermissions(storageDirectory);
            if (SHARED_DIRECTORY_PERMISSIONS.stream().anyMatch(permissions::contains)) {
                throw new IOException("Document storage directory permissions must be private");
            }
        }
    }

    private static FileAttribute<?>[] privateDirectoryAttributes() {
        return new FileAttribute<?>[] {
            PosixFilePermissions.asFileAttribute(
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE))
        };
    }

    private FileAttribute<?>[] privateFileAttributes() throws IOException {
        if (Files.getFileStore(storageDirectory).supportsFileAttributeView("posix")) {
            return new FileAttribute<?>[] {
                PosixFilePermissions.asFileAttribute(
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            };
        }
        return new FileAttribute<?>[0];
    }

    private FileStore nearestExistingFileStore() throws IOException {
        var existingPath = storageDirectory;
        while (existingPath != null && !Files.exists(existingPath)) {
            existingPath = existingPath.getParent();
        }
        if (existingPath == null) {
            throw new IOException("Document storage location is unavailable");
        }
        return Files.getFileStore(existingPath);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    enum CleanupResult {
        DELETED,
        RETAINED,
        BUSY
    }

    static final class StagedOriginal implements AutoCloseable {
        private final StoredOriginal original;
        private final Lock protection;

        private StagedOriginal(StoredOriginal original, Lock protection) {
            this.original = original;
            this.protection = protection;
        }

        StoredOriginal original() {
            return original;
        }

        @Override
        public void close() {
            protection.unlock();
        }
    }

    /** One directory cursor, retained by the background job between bounded batches. */
    static final class Scan implements AutoCloseable {
        private final DirectoryStream<Path> directory;
        private final Iterator<Path> entries;

        private Scan(DirectoryStream<Path> directory) {
            this.directory = directory;
            this.entries = directory.iterator();
        }

        boolean hasNext() {
            return entries.hasNext();
        }

        Optional<UUID> nextStorageId() {
            var name = entries.next().getFileName().toString();
            if (name.length() != 45 || !name.endsWith(".original")) {
                return Optional.empty();
            }
            try {
                var id = UUID.fromString(name.substring(0, 36));
                return name.equals(id + ".original") ? Optional.of(id) : Optional.empty();
            } catch (IllegalArgumentException invalidName) {
                return Optional.empty();
            }
        }

        @Override
        public void close() throws IOException {
            directory.close();
        }
    }

    record StoredOriginal(UUID storageId, long sizeInBytes, String sha256) {
        StoredOriginal {
            if (storageId == null || sizeInBytes < 1 || sha256 == null || sha256.length() != 64) {
                throw new IllegalArgumentException("Stored original metadata is invalid");
            }
        }

        String storageKey() {
            return storageId + ".original";
        }
    }
}
