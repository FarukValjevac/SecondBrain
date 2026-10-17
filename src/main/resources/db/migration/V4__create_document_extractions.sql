CREATE TABLE document_extractions (
    document_id uuid NOT NULL REFERENCES documents(document_id) ON DELETE CASCADE,
    processing_version bigint NOT NULL CHECK (processing_version > 0),
    extracted_at timestamptz NOT NULL,
    PRIMARY KEY (document_id, processing_version)
);

-- Keep text once, as numbered lines. Empty lines preserve source locations.
CREATE TABLE document_text_lines (
    document_id uuid NOT NULL,
    processing_version bigint NOT NULL,
    line_number integer NOT NULL CHECK (line_number > 0),
    line_text text NOT NULL
        CHECK (position(chr(10) in line_text) = 0 AND position(chr(13) in line_text) = 0),
    PRIMARY KEY (document_id, processing_version, line_number),
    FOREIGN KEY (document_id, processing_version)
        REFERENCES document_extractions(document_id, processing_version) ON DELETE CASCADE
);
