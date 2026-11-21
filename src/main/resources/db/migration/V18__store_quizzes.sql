-- Practice quizzes and the user's graded answers, kept only on this computer.
CREATE TABLE quizzes (
    quiz_id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL
);

CREATE TABLE quiz_questions (
    quiz_id uuid NOT NULL REFERENCES quizzes(quiz_id) ON DELETE CASCADE,
    position smallint NOT NULL CHECK (position BETWEEN 1 AND 5),
    document_id uuid NOT NULL REFERENCES documents(document_id) ON DELETE CASCADE,
    passage_id uuid NOT NULL,
    question text NOT NULL CHECK (char_length(question) BETWEEN 1 AND 300),
    answer text CHECK (char_length(answer) <= 400),
    verdict varchar(16) CHECK (verdict IN ('CORRECT', 'PARTLY_CORRECT', 'INCORRECT', 'UNGRADED')),
    explanation text CHECK (char_length(explanation) <= 600),
    PRIMARY KEY (quiz_id, position)
);

CREATE INDEX quiz_questions_document ON quiz_questions(document_id);

-- A quiz quotes its documents. When cleanup removes one of them, the whole quiz goes too.
CREATE FUNCTION remove_quiz_without_document() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM quizzes WHERE quiz_id = OLD.quiz_id;
    RETURN NULL;
END
$$;

CREATE TRIGGER quiz_document_removed
    AFTER DELETE ON quiz_questions
    FOR EACH ROW EXECUTE FUNCTION remove_quiz_without_document();
