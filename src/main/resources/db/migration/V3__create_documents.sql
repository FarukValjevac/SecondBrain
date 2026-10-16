CREATE TABLE documents (
    document_id uuid PRIMARY KEY,
    original_name text NOT NULL
        CHECK (length(btrim(original_name)) > 0 AND octet_length(original_name) <= 1024),
    document_type varchar(16) NOT NULL CHECK (document_type IN ('TXT', 'MARKDOWN')),
    storage_key uuid NOT NULL UNIQUE,
    content_sha256 varchar(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    original_size_bytes bigint NOT NULL CHECK (original_size_bytes > 0),
    processing_version bigint NOT NULL DEFAULT 1 CHECK (processing_version > 0),
    extraction_state varchar(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (extraction_state IN ('QUEUED', 'PROCESSING', 'EXTRACTED', 'FAILED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
