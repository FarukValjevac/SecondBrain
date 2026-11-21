-- One generated summary per document, valid only for the index generation it was built from.
CREATE TABLE document_summaries (
    document_id uuid PRIMARY KEY REFERENCES documents(document_id) ON DELETE CASCADE,
    indexing_version bigint NOT NULL CHECK (indexing_version > 0),
    summary text NOT NULL CHECK (char_length(summary) BETWEEN 1 AND 4000),
    generated_at timestamptz NOT NULL
);

CREATE TABLE document_summary_sources (
    document_id uuid NOT NULL REFERENCES document_summaries(document_id) ON DELETE CASCADE,
    position smallint NOT NULL CHECK (position BETWEEN 1 AND 5),
    passage_id uuid NOT NULL,
    PRIMARY KEY (document_id, position)
);

-- Each explicit regeneration needs its own event version, because events are deduplicated by
-- document, version and type.
CREATE TABLE document_summary_requests (
    document_id uuid PRIMARY KEY REFERENCES documents(document_id) ON DELETE CASCADE,
    last_request bigint NOT NULL CHECK (last_request > 0)
);
