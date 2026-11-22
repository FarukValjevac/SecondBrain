package dev.secondbrain.documents;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only access to a visible document's stored original, for viewing and download. */
public final class OriginalDocumentService {
    private final JdbcDocumentRepository documents;
    private final OriginalFileStore store;

    OriginalDocumentService(JdbcDocumentRepository documents, OriginalFileStore store) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** How an original can be presented; HTML is never rendered as a live page. */
    public enum Kind {
        PDF,
        DOCX,
        HTML,
        MARKDOWN,
        TEXT
    }

    /** Names are user text: never log them. */
    public record Original(UUID documentId, String name, Kind kind, long size) {
        public Original {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
        }

        @Override
        public String toString() {
            return "Original[kind=" + kind + ", size=" + size + "]";
        }
    }

    public Optional<Original> find(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId");
        return documents.findById(documentId).map(OriginalDocumentService::original);
    }

    /**
     * Opens the original of a document that is still visible. Empty when it is unknown or deleted;
     * the caller closes the stream.
     */
    public Optional<Opened> open(UUID documentId) throws IOException {
        Objects.requireNonNull(documentId, "documentId");
        var metadata = documents.findById(documentId);
        if (metadata.isEmpty()) return Optional.empty();
        return Optional.of(
                new Opened(original(metadata.get()), store.open(metadata.get().storageId())));
    }

    /** An open original; closing it closes the file. */
    public record Opened(Original original, InputStream stream) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    private static Original original(DocumentMetadata metadata) {
        var kind =
                switch (metadata.documentType()) {
                    case PDF -> Kind.PDF;
                    case DOCX -> Kind.DOCX;
                    case HTML -> Kind.HTML;
                    case MARKDOWN -> Kind.MARKDOWN;
                    case TXT -> Kind.TEXT;
                };
        return new Original(
                metadata.documentId(), metadata.originalName(), kind, metadata.originalSizeBytes());
    }
}
