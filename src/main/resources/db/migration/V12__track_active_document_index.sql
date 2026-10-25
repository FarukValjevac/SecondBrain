-- Existing staged generations remain unpublished. Activation is a guarded application operation.
ALTER TABLE documents ADD COLUMN active_indexing_version bigint
    CHECK (active_indexing_version > 0);

-- Active may differ from desired while a replacement generation is being prepared.
ALTER TABLE documents ADD CONSTRAINT documents_active_index_version
    FOREIGN KEY (document_id, active_indexing_version)
    REFERENCES document_index_versions(document_id, indexing_version)
    DEFERRABLE INITIALLY DEFERRED;
