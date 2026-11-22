-- Text recognised from scanned PDF pages. Each page is saved as soon as it is recognised, so long
-- scans continue after timeouts, retries and restarts. Rows go away with their document and are
-- removed once the document's text is stored.
CREATE TABLE document_ocr_pages (
    document_id uuid NOT NULL REFERENCES documents (document_id) ON DELETE CASCADE,
    processing_version bigint NOT NULL CHECK (processing_version > 0),
    page_number integer NOT NULL CHECK (page_number >= 1),
    page_count integer NOT NULL CHECK (page_count >= page_number AND page_count <= 1000),
    recognised_text text NOT NULL,
    recognised_at timestamptz NOT NULL,
    PRIMARY KEY (document_id, processing_version, page_number)
);

ALTER TABLE documents DROP CONSTRAINT documents_extraction_failure_check;
ALTER TABLE documents ADD CONSTRAINT documents_extraction_failure_check
    CHECK (extraction_failure IN (
        'INVALID_ENCODING', 'EMPTY_TEXT', 'INVALID_CONTENT', 'INPUT_TOO_LARGE',
        'TOO_MANY_LINES', 'SOURCE_UNAVAILABLE', 'CANCELLED',
        'PDF_UNREADABLE', 'PDF_ENCRYPTED', 'PDF_NO_TEXT', 'PDF_PAGE_LIMIT',
        'PDF_TEXT_LIMIT', 'PDF_WORK_LIMIT', 'PDF_TIME_LIMIT',
        'DOCX_UNREADABLE', 'OCR_UNAVAILABLE', 'OCR_NO_TEXT'));
