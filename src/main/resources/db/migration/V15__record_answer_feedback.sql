-- Feedback the user gave on an answer, kept only on this machine.
CREATE TABLE answer_feedback (
    feedback_id uuid PRIMARY KEY,
    helpful boolean NOT NULL,
    reason varchar(16) CHECK (reason IN ('WRONG', 'INCOMPLETE', 'WRONG_SOURCE')),
    question text NOT NULL CHECK (char_length(question) BETWEEN 1 AND 1200),
    answer text NOT NULL CHECK (char_length(answer) BETWEEN 1 AND 20000),
    created_at timestamptz NOT NULL,
    CHECK (NOT helpful OR reason IS NULL)
);

CREATE TABLE answer_feedback_sources (
    feedback_id uuid NOT NULL REFERENCES answer_feedback(feedback_id) ON DELETE CASCADE,
    position smallint NOT NULL CHECK (position BETWEEN 1 AND 5),
    document_id uuid NOT NULL REFERENCES documents(document_id) ON DELETE CASCADE,
    passage_id uuid NOT NULL,
    PRIMARY KEY (feedback_id, position)
);

CREATE INDEX answer_feedback_sources_document ON answer_feedback_sources(document_id);

-- The stored answer quotes its sources. When cleanup removes a cited document, the whole
-- feedback record goes with it rather than keeping text derived from deleted content.
CREATE FUNCTION remove_answer_feedback_without_source() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM answer_feedback WHERE feedback_id = OLD.feedback_id;
    RETURN NULL;
END
$$;

CREATE TRIGGER answer_feedback_source_removed
    AFTER DELETE ON answer_feedback_sources
    FOR EACH ROW EXECUTE FUNCTION remove_answer_feedback_without_source();
