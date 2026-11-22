-- Ask conversations, stored locally so they can be reopened and continued. Sources are references
-- only: no document text is copied here, and sources of deleted documents show as unavailable.
CREATE TABLE conversations (
    conversation_id uuid PRIMARY KEY,
    title text NOT NULL CHECK (title = btrim(title) AND char_length(title) BETWEEN 1 AND 80),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX conversations_recent ON conversations (updated_at DESC, conversation_id);

CREATE TABLE conversation_turns (
    conversation_id uuid NOT NULL REFERENCES conversations (conversation_id) ON DELETE CASCADE,
    position integer NOT NULL CHECK (position BETWEEN 1 AND 500),
    question text NOT NULL CHECK (char_length(question) BETWEEN 1 AND 1200),
    scope varchar(64) NOT NULL CHECK (scope = 'all' OR scope ~ '^folder:[0-9a-f-]{36}$'),
    outcome varchar(16) NOT NULL CHECK (outcome IN ('ANSWERED', 'INSUFFICIENT')),
    answer text NOT NULL CHECK (char_length(answer) <= 20000),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (conversation_id, position)
);

CREATE TABLE conversation_turn_sources (
    conversation_id uuid NOT NULL,
    position integer NOT NULL,
    source_number integer NOT NULL CHECK (source_number BETWEEN 1 AND 5),
    document_id uuid NOT NULL,
    passage_id uuid NOT NULL,
    PRIMARY KEY (conversation_id, position, source_number),
    FOREIGN KEY (conversation_id, position)
        REFERENCES conversation_turns (conversation_id, position) ON DELETE CASCADE
);
