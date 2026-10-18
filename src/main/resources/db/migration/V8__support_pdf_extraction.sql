ALTER TABLE documents DROP CONSTRAINT documents_document_type_check;
ALTER TABLE documents ADD CONSTRAINT documents_document_type_check
    CHECK (document_type IN ('TXT', 'MARKDOWN', 'PDF'));

ALTER TABLE documents DROP CONSTRAINT documents_extraction_failure_check;
ALTER TABLE documents ADD CONSTRAINT documents_extraction_failure_check
    CHECK (extraction_failure IN (
        'INVALID_ENCODING', 'EMPTY_TEXT', 'INVALID_CONTENT', 'INPUT_TOO_LARGE',
        'TOO_MANY_LINES', 'SOURCE_UNAVAILABLE', 'CANCELLED',
        'PDF_UNREADABLE', 'PDF_ENCRYPTED', 'PDF_NO_TEXT', 'PDF_PAGE_LIMIT',
        'PDF_TEXT_LIMIT', 'PDF_WORK_LIMIT', 'PDF_TIME_LIMIT'
    ));
