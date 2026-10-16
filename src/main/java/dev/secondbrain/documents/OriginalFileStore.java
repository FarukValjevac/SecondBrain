package dev.secondbrain.documents;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

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

    OriginalFileStore(
            @Value("${secondbrain.documents.storage-directory}") String storageDirectory,
            DocumentUploadPolicy uploadPolicy) {
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
        this.uploadPolicy = uploadPolicy;
    }

    /** Copies the stream without taking ownership of it. The caller must close the input. */
    StoredOriginal store(InputStream input) throws IOException {
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
