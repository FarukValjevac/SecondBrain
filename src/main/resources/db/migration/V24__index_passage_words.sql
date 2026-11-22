-- Exact-word search over passage text. The simple configuration keeps every word as written (lower
-- case, no stemming), so names, numbers and abbreviations match exactly; queries add prefix
-- matching for longer word forms.
ALTER TABLE document_passages
    ADD COLUMN search_terms tsvector GENERATED ALWAYS AS (to_tsvector('simple', passage_text)) STORED;
CREATE INDEX document_passages_search_terms ON document_passages USING gin (search_terms);
