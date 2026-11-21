package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FileTypeLabelTest {
    @ParameterizedTest
    @CsvSource({
        "notes.md, MD",
        "Report.Final.PDF, PDF",
        "archive.tar.gz, GZ",
        ".env, Document",
        "trailing., Document",
        "Page title without extension, Document",
        "odd.ext-with-dash, Document",
        "long.extension, Document"
    })
    void labelsOnlyShortAlphanumericExtensions(String name, String label) {
        assertThat(new FileTypeLabel().of(name)).isEqualTo(label);
    }
}
