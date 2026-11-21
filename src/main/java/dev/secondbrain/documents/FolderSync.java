package dev.secondbrain.documents;

import dev.secondbrain.documents.JdbcFolderFileRepository.Outcome;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Imports supported files from one local folder. Unchanged files are never imported again; a
 * changed file becomes a new document and the old one is deleted once the new one is searchable.
 * Symbolic links, hidden entries and anything outside the folder are skipped. Paths never reach
 * logs.
 */
public final class FolderSync {
    private static final int MAXIMUM_DEPTH = 16;
    private final FolderSyncProperties settings;
    private final DocumentUploadPolicy policy;
    private final DocumentAcceptanceService acceptance;
    private final DocumentDeletionService deletion;
    private final JdbcFolderFileRepository files;
    private final Clock clock;
    // Only one scan at a time, so a slow scan and the next timer tick never import twice.
    private final ReentrantLock scanning = new ReentrantLock();
    private volatile Status status = new Status(false, Optional.empty(), Optional.empty(), 0);

    FolderSync(
            FolderSyncProperties settings,
            DocumentUploadPolicy policy,
            DocumentAcceptanceService acceptance,
            DocumentDeletionService deletion,
            JdbcFolderFileRepository files,
            Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.acceptance = Objects.requireNonNull(acceptance, "acceptance");
        this.deletion = Objects.requireNonNull(deletion, "deletion");
        this.files = Objects.requireNonNull(files, "files");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What the library page shows; no paths or file names. */
    public record Status(
            boolean enabled, Optional<Instant> lastScan, Optional<String> problem, int tracked) {}

    public Status status() {
        return status;
    }

    /** Runs one bounded scan unless another is already running. Returns documents imported. */
    public int scan() {
        if (!scanning.tryLock()) return 0;
        try {
            var started = clock.instant();
            var correlation = UUID.randomUUID();
            final Path root;
            try {
                root = Path.of(settings.directory()).toRealPath();
                if (!Files.isDirectory(root)) throw new IOException("Not a directory");
            } catch (IOException | RuntimeException unavailable) {
                status =
                        new Status(
                                true, Optional.of(started), Optional.of("FOLDER_UNAVAILABLE"), 0);
                log("FOLDER_UNAVAILABLE");
                return 0;
            }
            var found = new ArrayList<Path>();
            boolean complete = walk(root, found);
            int imported = 0;
            for (var file : found) {
                if (Thread.currentThread().isInterrupted()) break;
                var relative =
                        root.relativize(file)
                                .toString()
                                .replace(file.getFileSystem().getSeparator(), "/");
                imported += visit(file, relative, started, correlation, imported);
            }
            // Only a complete walk can tell that a file was removed from disk.
            if (complete && settings.removeMissing())
                for (var missing : files.missingSince(started)) {
                    if (missing.documentVisible())
                        missing.documentId().ifPresent(id -> deletion.request(id, correlation));
                    files.remove(missing.path());
                }
            status =
                    new Status(
                            true,
                            Optional.of(started),
                            complete ? Optional.empty() : Optional.of("SCAN_LIMIT_REACHED"),
                            files.tracked());
            return imported;
        } finally {
            scanning.unlock();
        }
    }

    private boolean walk(Path root, List<Path> found) {
        try {
            var result =
                    new SimpleFileVisitor<Path>() {
                        boolean truncated;

                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path dir, BasicFileAttributes attributes) {
                            if (!dir.equals(root) && hidden(dir))
                                return FileVisitResult.SKIP_SUBTREE;
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(
                                Path file, BasicFileAttributes attributes) {
                            // Links are reported as links because they are not followed.
                            if (!attributes.isRegularFile() || hidden(file))
                                return FileVisitResult.CONTINUE;
                            if (found.size() == settings.maxFilesPerScan()) {
                                truncated = true;
                                return FileVisitResult.TERMINATE;
                            }
                            found.add(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException failure) {
                            truncated = true;
                            return FileVisitResult.CONTINUE;
                        }
                    };
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAXIMUM_DEPTH, result);
            return !result.truncated;
        } catch (IOException unavailable) {
            log("FOLDER_UNAVAILABLE");
            return false;
        }
    }

    /** Returns 1 when a document was imported for this file. */
    private int visit(Path file, String relative, Instant seen, UUID correlation, int imported) {
        try {
            // Never follow a path that resolves outside the folder.
            if (Files.isSymbolicLink(file)) return 0;
            var name = file.getFileName().toString();
            long size = Files.size(file);
            try {
                policy.validate(name, Math.max(size, 1));
            } catch (UploadRejectedException unsupported) {
                if (unsupported.reason() == UploadRejectedException.Reason.UNSUPPORTED_TYPE)
                    return 0;
            }
            var hash = hash(file, size);
            var known = files.find(relative);
            if (known.isPresent() && known.orElseThrow().sha256().equals(hash)) {
                files.touch(relative, seen);
                settle(known.orElseThrow(), correlation);
                return 0;
            }
            if (imported >= settings.maxImportsPerScan()) return 0;
            var previous = known.filter(JdbcFolderFileRepository.File::documentVisible);
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                var receipt = acceptance.accept(name, size, input, correlation);
                // Keep the old document searchable until its replacement is ready.
                var replaced =
                        previous.flatMap(
                                old -> {
                                    // A replacement that never became ready is superseded now.
                                    old.replacedDocumentId()
                                            .ifPresent(
                                                    stale ->
                                                            old.documentId()
                                                                    .ifPresent(
                                                                            id ->
                                                                                    deletion
                                                                                            .request(
                                                                                                    id,
                                                                                                    correlation)));
                                    return old.replacedDocumentId().or(old::documentId);
                                });
                files.save(
                        relative,
                        hash,
                        Outcome.IMPORTED,
                        Optional.of(receipt.documentId()),
                        replaced,
                        seen);
                return 1;
            } catch (UploadRejectedException rejected) {
                files.save(
                        relative, hash, Outcome.REJECTED, Optional.empty(), Optional.empty(), seen);
                return 0;
            }
        } catch (IOException unreadable) {
            // Retried on the next scan.
            return 0;
        }
    }

    /** Finishes a replacement, and stops tracking documents the user deleted. */
    private void settle(JdbcFolderFileRepository.File known, UUID correlation) {
        if (known.outcome() != Outcome.IMPORTED) return;
        if (!known.documentVisible()) {
            // Deleted in the app: do not bring it back unless the file changes.
            files.save(
                    known.path(),
                    known.sha256(),
                    Outcome.IGNORED,
                    Optional.empty(),
                    Optional.empty(),
                    clock.instant());
            return;
        }
        if (known.replacedDocumentId().isPresent() && known.documentReady()) {
            deletion.request(known.replacedDocumentId().orElseThrow(), correlation);
            files.clearReplaced(known.path());
        }
    }

    private String hash(Path file, long size) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            if (size > policy.maximumUploadBytes()) {
                // Too large to import: change detection uses size and modification time only.
                digest.update(
                        (size + ":" + Files.getLastModifiedTime(file).toMillis())
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                return HexFormat.of().formatHex(digest.digest());
            }
            try (var input =
                    new DigestInputStream(
                            Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static boolean hidden(Path path) {
        var name = path.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    private static void log(String code) {
        LoggerFactory.getLogger(FolderSync.class)
                .atWarn()
                .addKeyValue("operation", "folder_sync")
                .addKeyValue("code", code)
                .log("Folder sync scan incomplete");
    }
}
