package dev.secondbrain.folders;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Folder persistence. Callers own the transaction. */
final class JdbcFolderRepository {
    private final JdbcClient jdbc;

    JdbcFolderRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    List<Folder> all() {
        return jdbc.sql(
                        "SELECT folder_id, parent_id, name, color, depth FROM folders"
                                + " ORDER BY depth, lower(name), folder_id")
                .withQueryTimeout(5)
                .query((row, index) -> folder(row))
                .list();
    }

    Optional<Folder> find(UUID id) {
        return jdbc.sql(
                        "SELECT folder_id, parent_id, name, color, depth FROM folders"
                                + " WHERE folder_id = :id")
                .withQueryTimeout(5)
                .param("id", id)
                .query((row, index) -> folder(row))
                .optional();
    }

    /** Locks the row so concurrent rename, delete or child creation serialise. */
    Optional<Folder> lock(UUID id) {
        return jdbc.sql(
                        "SELECT folder_id, parent_id, name, color, depth FROM folders"
                                + " WHERE folder_id = :id FOR UPDATE")
                .withQueryTimeout(5)
                .param("id", id)
                .query((row, index) -> folder(row))
                .optional();
    }

    int count() {
        return jdbc.sql("SELECT count(*) FROM folders").query(Integer.class).single();
    }

    boolean siblingNamed(Optional<UUID> parent, String name, Optional<UUID> except) {
        return Boolean.TRUE.equals(
                jdbc.sql(
                                """
                                SELECT EXISTS (SELECT 1 FROM folders
                                    WHERE parent_id IS NOT DISTINCT FROM CAST(:parent AS uuid)
                                      AND lower(name) = lower(:name)
                                      AND folder_id IS DISTINCT FROM CAST(:except AS uuid))
                                """)
                        .withQueryTimeout(5)
                        .param("parent", parent.orElse(null))
                        .param("name", name)
                        .param("except", except.orElse(null))
                        .query(Boolean.class)
                        .single());
    }

    void insert(Folder folder, Instant now) {
        jdbc.sql(
                        """
                        INSERT INTO folders (folder_id, parent_id, name, color, depth, created_at, updated_at)
                        VALUES (:id, CAST(:parent AS uuid), :name, :color, :depth, :now, :now)
                        """)
                .withQueryTimeout(5)
                .param("id", folder.folderId())
                .param("parent", folder.parentId().orElse(null))
                .param("name", folder.name())
                .param("color", folder.color().name())
                .param("depth", folder.depth())
                .param("now", Timestamp.from(now))
                .update();
    }

    void update(UUID id, String name, FolderColor color, Instant now) {
        jdbc.sql(
                        "UPDATE folders SET name = :name, color = :color, updated_at = :now"
                                + " WHERE folder_id = :id")
                .withQueryTimeout(5)
                .param("id", id)
                .param("name", name)
                .param("color", color.name())
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * Moves the folder's documents and child folders to its parent, lifts every descendant one
     * level, then removes the folder. Documents are never deleted.
     */
    void deleteKeepingContents(Folder folder, Instant now) {
        var parent = folder.parentId().orElse(null);
        // Deeper descendants keep their parents and move up one level.
        jdbc.sql(
                        """
                        UPDATE folders SET depth = depth - 1, updated_at = :now
                        WHERE folder_id IN (
                            WITH RECURSIVE tree(folder_id) AS (
                                SELECT g.folder_id FROM folders g
                                JOIN folders c ON g.parent_id = c.folder_id
                                WHERE c.parent_id = :id
                                UNION ALL
                                SELECT f.folder_id FROM folders f JOIN tree t ON f.parent_id = t.folder_id
                            )
                            SELECT folder_id FROM tree)
                        """)
                .withQueryTimeout(5)
                .param("id", folder.folderId())
                .param("now", Timestamp.from(now))
                .update();
        // Direct children change parent and depth together, so every row stays valid.
        jdbc.sql(
                        "UPDATE folders SET parent_id = CAST(:parent AS uuid), depth = depth - 1,"
                                + " updated_at = :now WHERE parent_id = :id")
                .withQueryTimeout(5)
                .param("id", folder.folderId())
                .param("parent", parent)
                .param("now", Timestamp.from(now))
                .update();
        jdbc.sql("UPDATE documents SET folder_id = CAST(:parent AS uuid) WHERE folder_id = :id")
                .withQueryTimeout(5)
                .param("id", folder.folderId())
                .param("parent", parent)
                .update();
        jdbc.sql("DELETE FROM folders WHERE folder_id = :id")
                .withQueryTimeout(5)
                .param("id", folder.folderId())
                .update();
    }

    /** Returns false when the document is missing or deleted. */
    boolean placeDocument(UUID documentId, Optional<UUID> folderId) {
        return jdbc.sql(
                                "UPDATE documents SET folder_id = CAST(:folder AS uuid)"
                                        + " WHERE document_id = :id AND deleted_at IS NULL")
                        .withQueryTimeout(5)
                        .param("id", documentId)
                        .param("folder", folderId.orElse(null))
                        .update()
                == 1;
    }

    Optional<UUID> folderOf(UUID documentId) {
        return jdbc.sql(
                        "SELECT folder_id FROM documents WHERE document_id = :id"
                                + " AND deleted_at IS NULL AND folder_id IS NOT NULL")
                .withQueryTimeout(5)
                .param("id", documentId)
                .query(UUID.class)
                .optional();
    }

    /** Visible document counts per folder, not including subfolders. */
    Map<UUID, Integer> documentCounts() {
        var counts = new java.util.HashMap<UUID, Integer>();
        jdbc.sql(
                        "SELECT folder_id, count(*) AS documents FROM documents"
                                + " WHERE deleted_at IS NULL AND folder_id IS NOT NULL"
                                + " GROUP BY folder_id")
                .withQueryTimeout(5)
                .query(
                        (ResultSet row) -> {
                            counts.put(
                                    row.getObject("folder_id", UUID.class),
                                    row.getInt("documents"));
                        });
        return Map.copyOf(counts);
    }

    /** Visible documents in the folder and all of its subfolders, up to {@code limit}. */
    List<UUID> documentsWithin(UUID id, int limit) {
        return jdbc.sql(
                        """
                        WITH RECURSIVE tree(folder_id) AS (
                            SELECT folder_id FROM folders WHERE folder_id = :id
                            UNION ALL
                            SELECT f.folder_id FROM folders f JOIN tree t ON f.parent_id = t.folder_id
                        )
                        SELECT d.document_id FROM documents d JOIN tree t ON d.folder_id = t.folder_id
                        WHERE d.deleted_at IS NULL
                        ORDER BY d.created_at DESC, d.document_id
                        LIMIT :limit
                        """)
                .withQueryTimeout(5)
                .param("id", id)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    private static Folder folder(ResultSet row) throws SQLException {
        return new Folder(
                row.getObject("folder_id", UUID.class),
                Optional.ofNullable(row.getObject("parent_id", UUID.class)),
                row.getString("name"),
                FolderColor.valueOf(row.getString("color")),
                row.getInt("depth"));
    }
}
