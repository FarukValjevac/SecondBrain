ALTER TABLE document_index_versions
    ADD COLUMN passage_configuration_version integer CHECK (passage_configuration_version > 0),
    ADD COLUMN passage_count integer CHECK (passage_count BETWEEN 1 AND 20000),
    ADD CONSTRAINT document_index_passage_metadata CHECK (
        (passage_configuration_version IS NULL) = (passage_count IS NULL)
    );

-- Private staged content: storing passages does not activate an index for retrieval.
CREATE TABLE document_passages (
    passage_id uuid PRIMARY KEY,
    document_id uuid NOT NULL,
    indexing_version bigint NOT NULL,
    sequence_number integer NOT NULL CHECK (sequence_number BETWEEN 1 AND 20000),
    passage_text text NOT NULL CHECK (char_length(passage_text) BETWEEN 1 AND 1200),
    start_line integer NOT NULL CHECK (start_line > 0),
    start_column integer NOT NULL CHECK (start_column >= 0),
    end_line integer NOT NULL CHECK (end_line >= start_line),
    end_column integer NOT NULL CHECK (end_column >= 0),
    page_number integer CHECK (page_number > 0),
    CHECK (end_line > start_line OR end_column > start_column),
    UNIQUE (document_id, indexing_version, sequence_number),
    FOREIGN KEY (document_id, indexing_version)
        REFERENCES document_index_versions(document_id, indexing_version) ON DELETE CASCADE
);
