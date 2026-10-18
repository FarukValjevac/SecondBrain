CREATE TABLE document_index_versions (
    document_id uuid NOT NULL,
    indexing_version bigint NOT NULL CHECK (indexing_version > 0),
    extraction_version bigint NOT NULL CHECK (extraction_version > 0),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (document_id, indexing_version),
    FOREIGN KEY (document_id, extraction_version)
        REFERENCES document_extractions(document_id, processing_version) ON DELETE CASCADE
);

ALTER TABLE documents ADD COLUMN desired_indexing_version bigint
    CHECK (desired_indexing_version > 0);

-- Version insertion and selection must commit together; unselected documents remain NULL.
ALTER TABLE documents ADD CONSTRAINT documents_desired_index_version
    FOREIGN KEY (document_id, desired_indexing_version)
    REFERENCES document_index_versions(document_id, indexing_version)
    DEFERRABLE INITIALLY DEFERRED;
