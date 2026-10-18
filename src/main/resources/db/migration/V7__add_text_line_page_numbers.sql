ALTER TABLE document_text_lines
    ADD COLUMN page_number integer
    CONSTRAINT document_text_lines_positive_page CHECK (page_number > 0);
