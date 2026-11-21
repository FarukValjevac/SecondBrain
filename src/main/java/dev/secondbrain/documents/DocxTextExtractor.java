package dev.secondbrain.documents;

import dev.secondbrain.documents.TextExtractionException.Reason;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

/**
 * Reads the body text of a Word .docx file with the JDK's zip and XML readers. Only
 * word/document.xml is parsed; headers, footers, comments, deleted revisions and images are
 * ignored. Entry count and decompressed size are bounded against zip bombs, and DTDs and external
 * entities are disabled against XML entity attacks.
 */
final class DocxTextExtractor {
    static final int MAXIMUM_ENTRIES = 10_000;
    private static final String BODY = "word/document.xml";
    private static final String WORD =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private final long maximumBodyBytes;
    private final XMLInputFactory xml;

    DocxTextExtractor(long maximumBodyBytes) {
        if (maximumBodyBytes < 1)
            throw new IllegalArgumentException("Maximum Word body size must be positive");
        this.maximumBodyBytes = maximumBodyBytes;
        xml = XMLInputFactory.newFactory();
        xml.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xml.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        xml.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        xml.setProperty(XMLInputFactory.IS_COALESCING, true);
    }

    /** The caller owns the input stream; it is not closed here. */
    List<ExtractedText.Line> extract(InputStream input) {
        Objects.requireNonNull(input, "input");
        // Not closed: closing the zip reader would also close the caller-owned stream.
        var zip = new ZipInputStream(input);
        try {
            for (var count = 1; count <= MAXIMUM_ENTRIES; count++) {
                TextLines.checkInterruption();
                var entry = zip.getNextEntry();
                // Encrypted or legacy files are not zip archives and contain no entries.
                if (entry == null) break;
                if (BODY.equals(entry.getName()))
                    return TextLines.split(body(new BoundedEntry(zip, maximumBodyBytes)));
            }
        } catch (ZipException | XMLStreamException corrupt) {
            // The XML reader may wrap a budget failure raised while it was reading input.
            for (Throwable cause = corrupt.getCause(); cause != null; cause = cause.getCause())
                if (cause instanceof TextExtractionException limit) throw limit;
            throw new TextExtractionException(Reason.DOCX_UNREADABLE, corrupt);
        } catch (IOException unavailable) {
            throw new TextExtractionException(Reason.SOURCE_UNAVAILABLE, unavailable);
        }
        throw new TextExtractionException(Reason.DOCX_UNREADABLE);
    }

    private String body(InputStream entry) throws XMLStreamException {
        var reader = xml.createXMLStreamReader(entry);
        var text = new StringBuilder();
        var inText = false;
        var events = 0;
        try {
            while (reader.hasNext()) {
                if ((++events & 0xFFF) == 0) TextLines.checkInterruption();
                var event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && WORD.equals(reader.getNamespaceURI())) {
                    switch (reader.getLocalName()) {
                        case "t" -> inText = true;
                        case "tab" -> text.append('\t');
                        case "br", "cr" -> text.append('\n');
                        default -> {}
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && WORD.equals(reader.getNamespaceURI())) {
                    switch (reader.getLocalName()) {
                        case "t" -> inText = false;
                        case "p" -> text.append('\n');
                        default -> {}
                    }
                } else if (inText && event == XMLStreamConstants.CHARACTERS) {
                    text.append(reader.getText());
                }
            }
        } finally {
            reader.close();
        }
        return text.toString();
    }

    /** Fails once the decompressed body exceeds its budget; a zip bomb stops early. */
    private static final class BoundedEntry extends FilterInputStream {
        private long remaining;

        private BoundedEntry(InputStream input, long maximumBytes) {
            super(input);
            remaining = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            var value = in.read();
            if (value >= 0) consume(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            var count = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
            if (count > 0) consume(count);
            return count;
        }

        private void consume(int count) {
            if (count > remaining) throw new TextExtractionException(Reason.INPUT_TOO_LARGE);
            remaining -= count;
        }

        @Override
        public void close() {
            // The zip stream belongs to the caller.
        }
    }
}
