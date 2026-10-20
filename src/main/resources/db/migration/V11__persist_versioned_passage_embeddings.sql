ALTER TABLE document_index_versions
    ADD COLUMN embedding_model_id varchar(128) CHECK (embedding_model_id = '@cf/baai/bge-m3'),
    ADD COLUMN embedding_dimensions integer CHECK (embedding_dimensions = 1024),
    ADD CONSTRAINT document_index_embedding_metadata CHECK (
        (embedding_model_id IS NULL) = (embedding_dimensions IS NULL)
        AND (embedding_model_id IS NULL OR passage_count IS NOT NULL)
    ),
    ADD CONSTRAINT document_index_embedding_model UNIQUE (
        document_id, indexing_version, embedding_model_id, embedding_dimensions
    );

-- Composite ownership prevents a valid passage ID being attached to a different generation.
ALTER TABLE document_passages
    ADD CONSTRAINT document_passage_generation UNIQUE (passage_id, document_id, indexing_version);

-- Private staging only: rows do not activate an index or acknowledge any event delivery.
CREATE TABLE document_embeddings (
    passage_id uuid PRIMARY KEY,
    document_id uuid NOT NULL,
    indexing_version bigint NOT NULL,
    model_id varchar(128) NOT NULL,
    dimensions integer NOT NULL,
    embedding vector(1024) NOT NULL CHECK (vector_norm(embedding) > 0),
    FOREIGN KEY (passage_id, document_id, indexing_version)
        REFERENCES document_passages(passage_id, document_id, indexing_version) ON DELETE CASCADE,
    FOREIGN KEY (document_id, indexing_version, model_id, dimensions)
        REFERENCES document_index_versions(
            document_id, indexing_version, embedding_model_id, embedding_dimensions
        ) ON DELETE CASCADE
);

CREATE INDEX document_embeddings_generation ON document_embeddings(document_id, indexing_version);
