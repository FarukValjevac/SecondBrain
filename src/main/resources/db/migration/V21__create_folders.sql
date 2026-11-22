-- Folders organise documents. A document sits in at most one folder; deleting a folder never
-- deletes documents (the application moves them to the parent first; SET NULL is the backstop).
CREATE TABLE folders (
    folder_id uuid PRIMARY KEY,
    parent_id uuid REFERENCES folders (folder_id) ON DELETE RESTRICT,
    name text NOT NULL CHECK (name = btrim(name) AND char_length(name) BETWEEN 1 AND 80),
    color varchar(16) NOT NULL
        CHECK (color IN ('VIOLET', 'BLUE', 'TEAL', 'GREEN', 'AMBER', 'ORANGE', 'ROSE', 'SLATE')),
    depth smallint NOT NULL CHECK (depth BETWEEN 1 AND 3),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (parent_id IS DISTINCT FROM folder_id),
    CHECK ((parent_id IS NULL) = (depth = 1))
);

-- Names are unique among siblings, ignoring case; top-level folders share one namespace.
CREATE UNIQUE INDEX folders_sibling_name
    ON folders (COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));
CREATE INDEX folders_parent ON folders (parent_id);

ALTER TABLE documents
    ADD COLUMN folder_id uuid REFERENCES folders (folder_id) ON DELETE SET NULL;
CREATE INDEX documents_folder ON documents (folder_id, created_at DESC) WHERE deleted_at IS NULL;
