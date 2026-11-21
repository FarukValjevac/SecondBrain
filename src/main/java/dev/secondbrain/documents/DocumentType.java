package dev.secondbrain.documents;

/** How a stored original is read; every other plain-text format is stored as TXT. */
enum DocumentType {
    TXT,
    MARKDOWN,
    PDF,
    HTML,
    DOCX
}
