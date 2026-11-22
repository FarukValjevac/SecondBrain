package dev.secondbrain.folders;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Folder rules: names are trimmed and unique among siblings (ignoring case), nesting stops at three
 * levels, there are at most 200 folders, and deleting a folder keeps its contents.
 */
public final class FolderService {
    public static final int MAXIMUM_FOLDERS = 200;

    private final JdbcFolderRepository folders;
    private final TransactionTemplate transactions;
    private final Clock clock;

    FolderService(JdbcFolderRepository folders, PlatformTransactionManager manager, Clock clock) {
        this.folders = Objects.requireNonNull(folders, "folders");
        this.clock = Objects.requireNonNull(clock, "clock");
        transactions = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transactions.setTimeout(5);
    }

    /** Every folder, parents before children; bounded by {@link #MAXIMUM_FOLDERS}. */
    public FolderTree tree() {
        return Objects.requireNonNull(
                transactions.execute(
                        status -> new FolderTree(folders.all(), folders.documentCounts())));
    }

    public Optional<Folder> find(UUID id) {
        Objects.requireNonNull(id, "id");
        return Objects.requireNonNull(transactions.execute(status -> folders.find(id)));
    }

    public Folder create(String name, Optional<UUID> parentId, FolderColor color) {
        var cleanName = Folder.name(name);
        Objects.requireNonNull(parentId, "parentId");
        Objects.requireNonNull(color, "color");
        return Objects.requireNonNull(
                transactions.execute(
                        status -> {
                            int depth = 1;
                            if (parentId.isPresent()) {
                                var parent =
                                        folders.lock(parentId.get())
                                                .orElseThrow(
                                                        () ->
                                                                new FolderException(
                                                                        FolderException.Reason
                                                                                .NOT_FOUND));
                                if (!parent.canHaveChildren())
                                    throw new FolderException(FolderException.Reason.TOO_DEEP);
                                depth = parent.depth() + 1;
                            }
                            if (folders.count() >= MAXIMUM_FOLDERS)
                                throw new FolderException(FolderException.Reason.TOO_MANY);
                            if (folders.siblingNamed(parentId, cleanName, Optional.empty()))
                                throw new FolderException(FolderException.Reason.DUPLICATE_NAME);
                            var folder =
                                    new Folder(
                                            UUID.randomUUID(), parentId, cleanName, color, depth);
                            folders.insert(folder, clock.instant());
                            return folder;
                        }));
    }

    public void update(UUID id, String name, FolderColor color) {
        var cleanName = Folder.name(name);
        Objects.requireNonNull(color, "color");
        transactions.executeWithoutResult(
                status -> {
                    var folder = locked(id);
                    if (folders.siblingNamed(folder.parentId(), cleanName, Optional.of(id)))
                        throw new FolderException(FolderException.Reason.DUPLICATE_NAME);
                    folders.update(id, cleanName, color, clock.instant());
                });
    }

    /**
     * Deletes the folder after moving its documents and subfolders to its parent. A name clash
     * among the lifted subfolders is refused rather than merged.
     */
    public void delete(UUID id) {
        transactions.executeWithoutResult(
                status -> {
                    var folder = locked(id);
                    var all = folders.all();
                    var siblings = new ArrayList<String>();
                    for (var other : all)
                        if (other.parentId().equals(folder.parentId())
                                && !other.folderId().equals(id))
                            siblings.add(other.name().toLowerCase(java.util.Locale.ROOT));
                    for (var child : all)
                        if (child.parentId().equals(Optional.of(id))
                                && siblings.contains(
                                        child.name().toLowerCase(java.util.Locale.ROOT)))
                            throw new FolderException(FolderException.Reason.DUPLICATE_NAME);
                    folders.deleteKeepingContents(folder, clock.instant());
                });
    }

    /** Moves a document; an empty folder means unfiled. Returns false for a missing document. */
    public boolean move(UUID documentId, Optional<UUID> folderId) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(folderId, "folderId");
        return Boolean.TRUE.equals(
                transactions.execute(
                        status -> {
                            if (folderId.isPresent() && folders.lock(folderId.get()).isEmpty())
                                throw new FolderException(FolderException.Reason.NOT_FOUND);
                            return folders.placeDocument(documentId, folderId);
                        }));
    }

    public Optional<UUID> folderOf(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId");
        return Objects.requireNonNull(transactions.execute(status -> folders.folderOf(documentId)));
    }

    /** Visible documents in the folder and its subfolders, at most {@code limit}. */
    public List<UUID> documentsWithin(UUID folderId, int limit) {
        Objects.requireNonNull(folderId, "folderId");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid limit");
        return Objects.requireNonNull(
                transactions.execute(
                        status -> {
                            if (folders.find(folderId).isEmpty())
                                throw new FolderException(FolderException.Reason.NOT_FOUND);
                            return folders.documentsWithin(folderId, limit);
                        }));
    }

    private Folder locked(UUID id) {
        Objects.requireNonNull(id, "id");
        return folders.lock(id)
                .orElseThrow(() -> new FolderException(FolderException.Reason.NOT_FOUND));
    }

    /** All folders with their direct document counts; navigation helpers for views. */
    public record FolderTree(List<Folder> folders, Map<UUID, Integer> documentCounts) {
        public FolderTree {
            folders = List.copyOf(folders);
            documentCounts = Map.copyOf(documentCounts);
        }

        public Optional<Folder> find(UUID id) {
            for (var folder : folders) if (folder.folderId().equals(id)) return Optional.of(folder);
            return Optional.empty();
        }

        public List<Folder> children(Optional<UUID> parent) {
            var children = new ArrayList<Folder>();
            for (var folder : folders) if (folder.parentId().equals(parent)) children.add(folder);
            return List.copyOf(children);
        }

        public List<Folder> topLevel() {
            return children(Optional.empty());
        }

        public List<Folder> childrenOf(Folder folder) {
            return children(Optional.of(folder.folderId()));
        }

        /** Root first, ending with the folder itself. */
        public List<Folder> path(UUID id) {
            var path = new ArrayList<Folder>();
            var current = find(id);
            while (current.isPresent() && path.size() <= Folder.MAXIMUM_DEPTH) {
                path.add(current.get());
                current = current.get().parentId().flatMap(this::find);
            }
            Collections.reverse(path);
            return List.copyOf(path);
        }

        /** Folders in display order (each parent followed by its children) for choosers. */
        public List<Folder> ordered() {
            var ordered = new ArrayList<Folder>();
            for (var top : topLevel()) add(top, ordered);
            return List.copyOf(ordered);
        }

        private void add(Folder folder, List<Folder> ordered) {
            ordered.add(folder);
            for (var child : childrenOf(folder)) add(child, ordered);
        }

        /** Direct documents plus all documents in subfolders. */
        public int documentsIncludingSubfolders(Folder folder) {
            int total = documentCounts.getOrDefault(folder.folderId(), 0);
            for (var child : childrenOf(folder)) total += documentsIncludingSubfolders(child);
            return total;
        }

        /** "Psychology › Biases" for choosers. */
        public String label(Folder folder) {
            var names = new ArrayList<String>();
            for (var step : path(folder.folderId())) names.add(step.name());
            return String.join(" › ", names);
        }

        public boolean isEmpty() {
            return folders.isEmpty();
        }
    }
}
