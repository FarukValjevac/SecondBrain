-- Study cards generated from one document; each cites the passage it was built from.
CREATE TABLE flashcards (
    card_id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES documents(document_id) ON DELETE CASCADE,
    passage_id uuid NOT NULL,
    question text NOT NULL CHECK (char_length(question) BETWEEN 1 AND 300),
    answer text NOT NULL CHECK (char_length(answer) BETWEEN 1 AND 600),
    created_at timestamptz NOT NULL,
    due_at timestamptz NOT NULL,
    interval_days integer NOT NULL CHECK (interval_days >= 0),
    ease double precision NOT NULL CHECK (ease >= 1.3),
    repetitions integer NOT NULL CHECK (repetitions >= 0)
);

CREATE INDEX flashcards_due ON flashcards(due_at);
CREATE INDEX flashcards_document ON flashcards(document_id);
