-- Files seen in the optional watched folder. Paths are relative to that folder and private.
CREATE TABLE folder_files (
    relative_path text PRIMARY KEY CHECK (char_length(relative_path) BETWEEN 1 AND 4096),
    content_sha256 char(64) NOT NULL,
    outcome varchar(16) NOT NULL CHECK (outcome IN ('IMPORTED', 'REJECTED', 'IGNORED')),
    document_id uuid REFERENCES documents(document_id) ON DELETE SET NULL,
    -- The previous document of a changed file, deleted once the new one is searchable.
    replaced_document_id uuid REFERENCES documents(document_id) ON DELETE SET NULL,
    seen_at timestamptz NOT NULL
);
