-- Highlights and notes made while reading. They belong to one extraction of a document (its line
-- numbers), so reprocessing hides them, and they are removed together with the document.
CREATE TABLE document_highlights (
    highlight_id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES documents (document_id) ON DELETE CASCADE,
    processing_version bigint NOT NULL CHECK (processing_version > 0),
    start_line integer NOT NULL CHECK (start_line >= 1),
    end_line integer NOT NULL CHECK (end_line >= start_line AND end_line - start_line <= 500),
    quote text NOT NULL CHECK (char_length(quote) BETWEEN 1 AND 2000),
    note text NOT NULL DEFAULT '' CHECK (char_length(note) <= 4000),
    color varchar(16) NOT NULL CHECK (color IN ('YELLOW', 'GREEN', 'BLUE', 'PINK')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX document_highlights_document ON document_highlights (document_id, processing_version, start_line);
CREATE INDEX document_highlights_recent ON document_highlights (updated_at DESC);
