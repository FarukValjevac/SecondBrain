package dev.secondbrain.imports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.time.Instant;

class WebPageImportServiceTest {
    @ParameterizedTest
    @CsvSource({
        "https://Example.com/guides/Java Notes.html,text/html,example.com-java-notes.html",
        "https://example.com/,text/html,example.com.html",
        "https://example.com/readme,text/markdown,example.com-readme.md",
        "https://example.com/a/b/../c.txt,text/plain,example.com-c.txt"
    })
    void namesImportedPagesAfterTheirHostAndLastPathSegment(
            String url, String mediaType, String expected) {
        var page =
                new PageFetcher.Page(
                        URI.create(url.replace(" ", "%20")).normalize(),
                        mediaType,
                        new byte[] {1},
                        Instant.EPOCH);
        assertThat(WebPageImportService.fileName(page)).isEqualTo(expected);
    }
}
