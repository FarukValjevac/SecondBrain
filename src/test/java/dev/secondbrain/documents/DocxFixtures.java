package dev.secondbrain.documents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds minimal but valid Word .docx files; each argument becomes one paragraph. */
public final class DocxFixtures {
    private static final String WORD =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private DocxFixtures() {}

    public static byte[] paragraphs(String... paragraphs) throws IOException {
        var body = new StringBuilder();
        for (var paragraph : paragraphs)
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                    .append(escape(paragraph))
                    .append("</w:t></w:r></w:p>");
        return document(body.toString());
    }

    /** Wraps raw WordprocessingML body content in a complete package. */
    static byte[] document(String bodyXml) throws IOException {
        return zip(
                "word/document.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<w:document xmlns:w=\""
                        + WORD
                        + "\"><w:body>"
                        + bodyXml
                        + "</w:body></w:document>");
    }

    static byte[] zip(String bodyName, String bodyXml) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            entry(
                    zip,
                    "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types"
                            + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/"
                            + "vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                            + "</Types>");
            entry(zip, bodyName, bodyXml);
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
