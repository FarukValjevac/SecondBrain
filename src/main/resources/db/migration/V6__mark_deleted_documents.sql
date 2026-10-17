-- Retain storage ownership and processing fences until durable cleanup completes.
ALTER TABLE documents ADD COLUMN deleted_at timestamptz;

CREATE INDEX documents_visible_library ON documents (created_at DESC, document_id DESC)
    WHERE deleted_at IS NULL;
