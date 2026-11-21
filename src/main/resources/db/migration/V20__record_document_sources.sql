-- Where an imported web page came from and when it was fetched.
CREATE TABLE document_sources (
    document_id uuid PRIMARY KEY REFERENCES documents(document_id) ON DELETE CASCADE,
    source_url text NOT NULL CHECK (char_length(source_url) BETWEEN 1 AND 2048),
    fetched_at timestamptz NOT NULL
);
