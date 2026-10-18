package dev.secondbrain.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.secondbrain.documents.DocumentAcceptanceService;
import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.DocumentInspectionService.DocumentSummary;
import dev.secondbrain.documents.DocumentInspectionService.LibraryPage;
import dev.secondbrain.documents.ExtractionState;
import dev.secondbrain.documents.UploadRejectedException;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@WebMvcTest(
        controllers = {LibraryController.class, DocumentUploadFormController.class},
        properties = "secondbrain.documents.max-upload-size=2KB")
@ExtendWith(OutputCaptureExtension.class)
class LibraryControllerTest {

    private final MockMvc mvc;
    @MockitoBean private DocumentAcceptanceService acceptanceService;
    @MockitoBean private DocumentInspectionService inspection;

    @BeforeEach
    void emptyLibrary() {
        when(inspection.list(anyInt())).thenReturn(new LibraryPage(List.of(), 0, false));
    }

    @Autowired
    LibraryControllerTest(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void rendersAnEmptyLibraryWithoutDocumentLinksOrPagination() throws Exception {
        var html =
                mvc.perform(MockMvcRequestBuilders.get("/"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Assertions.assertThat(html)
                .contains("Your documents", "No documents on this page.")
                .doesNotContain("href=\"/documents/", "aria-label=\"Document pages\"");
        verify(inspection).list(0);
    }

    @Test
    void rendersEscapedNamesStatesAndDetailLinksWithoutLoggingNames(CapturedOutput output)
            throws Exception {
        var id = UUID.randomUUID();
        when(inspection.list(0))
                .thenReturn(
                        new LibraryPage(
                                List.of(
                                        new DocumentSummary(
                                                id,
                                                "sentinel-private-<script>alert(1)</script>.md",
                                                ExtractionState.EXTRACTED,
                                                Instant.parse("2026-01-01T00:00:00Z"))),
                                0,
                                false));
        var html =
                mvc.perform(MockMvcRequestBuilders.get("/"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Assertions.assertThat(html)
                .contains(
                        "href=\"/documents/" + id + "\"",
                        "EXTRACTED",
                        "href=\"/documents/" + id + "/delete\"",
                        "aria-label=\"Delete sentinel-private-&lt;script&gt;alert(1)&lt;/script&gt;.md\"",
                        "sentinel-private-&lt;script&gt;alert(1)&lt;/script&gt;.md",
                        "datetime=\"2026-01-01T00:00:00Z\"")
                .doesNotContain("<script>", "No documents on this page.", "rel=\"next\"");
        Assertions.assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @Test
    void linksAdjacentPagesAndStopsAtTheLastPage() throws Exception {
        when(inspection.list(1)).thenReturn(new LibraryPage(List.of(), 1, true));
        var middle =
                mvc.perform(MockMvcRequestBuilders.get("/").param("page", "1"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Assertions.assertThat(middle)
                .contains(
                        "rel=\"prev\"",
                        "href=\"/?page=0\"",
                        "rel=\"next\"",
                        "href=\"/?page=2\"",
                        "Page 2");
        when(inspection.list(2)).thenReturn(new LibraryPage(List.of(), 2, false));
        var last =
                mvc.perform(MockMvcRequestBuilders.get("/").param("page", "2"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Assertions.assertThat(last).contains("href=\"/?page=1\"").doesNotContain("rel=\"next\"");
        verify(inspection).list(1);
        verify(inspection).list(2);
    }

    @Test
    void rejectsInvalidPagesBeforeReadingDocuments() throws Exception {
        for (var page : List.of("-1", "sentinel-private-query", "2147483648")) {
            var body =
                    mvc.perform(MockMvcRequestBuilders.get("/").param("page", page))
                            .andExpect(status().isBadRequest())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            Assertions.assertThat(body).isEqualTo("Use a non-negative page number.");
        }
        verifyNoInteractions(inspection);
    }

    @Test
    void reportsUnavailableStorageWithoutExposingItsCause(CapturedOutput output) throws Exception {
        when(inspection.list(0))
                .thenThrow(new DataAccessResourceFailureException("sentinel-private-path"));
        var body =
                mvc.perform(MockMvcRequestBuilders.get("/"))
                        .andExpect(status().isServiceUnavailable())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Assertions.assertThat(body)
                .isEqualTo("Documents are temporarily unavailable. Try again later.");
        Assertions.assertThat(output.getAll()).doesNotContain("sentinel-private");
    }

    @Test
    void rendersTheNativeUploadFormWithConfiguredGuidance() throws Exception {
        var response =
                mvc.perform(MockMvcRequestBuilders.get("/"))
                        .andExpect(MockMvcResultMatchers.status().isOk())
                        .andExpect(
                                MockMvcResultMatchers.content()
                                        .contentTypeCompatibleWith(MediaType.TEXT_HTML))
                        .andExpect(MockMvcResultMatchers.view().name("library"))
                        .andReturn()
                        .getResponse();

        Assertions.assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains(
                        "Document library",
                        "Add a document",
                        "action=\"/documents/upload\"",
                        "enctype=\"multipart/form-data\"",
                        "method=\"post\"",
                        "name=\"file\"",
                        "accept=\".txt,.md,.markdown\"",
                        "aria-describedby=\"upload-help\"",
                        "2KB",
                        "Upload document")
                .contains("href=\"/css/library.css\"")
                .doesNotContain("0 documents", "Your library is empty.", "th:href");

        mvc.perform(MockMvcRequestBuilders.get("/css/library.css"))
                .andExpect(status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentTypeCompatibleWith("text/css"));
    }

    @Test
    void redirectsAcceptedMarkdownAndShowsOneTimeQueuedFeedback(CapturedOutput output)
            throws Exception {
        var correlationId = UUID.randomUUID();
        var receipt =
                new DocumentAcceptanceService.Receipt(UUID.randomUUID(), ExtractionState.QUEUED);
        var bytes = "sentinel-private-content".getBytes(StandardCharsets.UTF_8);
        when(acceptanceService.accept(
                        eq("sentinel-private-name.md"),
                        eq((long) bytes.length),
                        any(InputStream.class),
                        eq(correlationId)))
                .thenAnswer(
                        invocation -> {
                            Assertions.assertThat(
                                            ((InputStream) invocation.getArgument(2))
                                                    .readAllBytes())
                                    .containsExactly(bytes);
                            return receipt;
                        });
        var result =
                mvc.perform(
                                multipart("/documents/upload")
                                        .file(
                                                new MockMultipartFile(
                                                        "file",
                                                        "sentinel-private-name.md",
                                                        "text/markdown",
                                                        bytes))
                                        .header("X-Correlation-ID", correlationId))
                        .andExpect(status().isSeeOther())
                        .andExpect(redirectedUrl("/"))
                        .andExpect(flash().attribute("uploadReceipt", receipt))
                        .andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        Assertions.assertThat(session).isNotNull();
        var firstPage =
                mvc.perform(MockMvcRequestBuilders.get("/").session(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Assertions.assertThat(firstPage).contains("Saved and queued", "QUEUED", "role=\"status\"");
        var refreshedPage =
                mvc.perform(MockMvcRequestBuilders.get("/").session(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Assertions.assertThat(refreshedPage).doesNotContain("Saved and queued");
        verify(acceptanceService)
                .accept(
                        eq("sentinel-private-name.md"),
                        eq((long) bytes.length),
                        any(InputStream.class),
                        eq(correlationId));
        Assertions.assertThat(output.getOut()).doesNotContain("sentinel-private");
    }

    @Test
    void redirectsEachValidationFailureToSafeBrowserFeedback() throws Exception {
        for (var reason : UploadRejectedException.Reason.values()) {
            doThrow(new UploadRejectedException(reason))
                    .when(acceptanceService)
                    .accept(any(), anyLong(), any(), any());
            var result =
                    mvc.perform(multipart("/documents/upload").file(file()))
                            .andExpect(status().isSeeOther())
                            .andExpect(redirectedUrl("/"))
                            .andExpect(flash().attribute("uploadError", reason.message()))
                            .andReturn();
            var page =
                    mvc.perform(MockMvcRequestBuilders.get("/").flashAttrs(result.getFlashMap()))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            Assertions.assertThat(page)
                    .contains("role=\"alert\"", reason.message())
                    .doesNotContain("sentinel-private");
        }
    }

    @Test
    void redirectsMissingAndRepeatedFilePartsWithoutCallingAcceptance() throws Exception {
        mvc.perform(multipart("/documents/upload"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attributeExists("uploadError"));
        mvc.perform(multipart("/documents/upload").file(file()).file(file()))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("uploadError", "Upload one file at a time."));
        verifyNoInteractions(acceptanceService);
    }

    @Test
    void rendersStorageAndDatabaseFailuresWithoutPrivateMessages(CapturedOutput output)
            throws Exception {
        for (var failure :
                new Exception[] {
                    new IOException("sentinel-private-storage"),
                    new DataAccessResourceFailureException("sentinel-private-database")
                }) {
            doThrow(failure).when(acceptanceService).accept(any(), anyLong(), any(), any());
            var result =
                    mvc.perform(multipart("/documents/upload").file(file()))
                            .andExpect(status().isSeeOther())
                            .andExpect(redirectedUrl("/"))
                            .andExpect(
                                    flash().attribute(
                                                    "uploadError",
                                                    "The upload could not be completed. Try again later."))
                            .andReturn();
            var page =
                    mvc.perform(MockMvcRequestBuilders.get("/").flashAttrs(result.getFlashMap()))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            Assertions.assertThat(page)
                    .contains("Upload could not be completed")
                    .doesNotContain("sentinel-private");
        }
        Assertions.assertThat(output.getOut()).doesNotContain("sentinel-private");
    }

    @Test
    void escapesFeedbackTextBeforeRenderingIt() throws Exception {
        var page =
                mvc.perform(
                                MockMvcRequestBuilders.get("/")
                                        .flashAttr(
                                                "uploadError", "<script>alert('example')</script>"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Assertions.assertThat(page).contains("&lt;script&gt;").doesNotContain("<script>");
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile(
                "file", "sentinel-private-name.txt", "text/plain", new byte[] {1});
    }

    @Test
    void redirectsMultipartLimitFailuresToTheForm() throws Exception {
        var file =
                new MockMultipartFile("file", "notes.txt", "text/plain", new byte[] {1}) {
                    @Override
                    public InputStream getInputStream() {
                        throw new MaxUploadSizeExceededException(1);
                    }
                };
        mvc.perform(multipart("/documents/upload").file(file))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/"))
                .andExpect(
                        flash().attribute(
                                        "uploadError",
                                        UploadRejectedException.Reason.FILE_TOO_LARGE.message()));
        verifyNoInteractions(acceptanceService);
    }

    @Test
    void correlatesTheResponseAndJsonLogWithoutRequestContent(CapturedOutput output)
            throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(
                        MockMvcRequestBuilders.get("/")
                                .header("X-Correlation-ID", id)
                                .header("Authorization", "Bearer sentinel-private-credential")
                                .queryParam("q", "sentinel-private-query"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.header().string("X-Correlation-ID", id));

        var lines =
                output.getOut()
                        .lines()
                        .filter(line -> line.contains(id) && line.contains("http_request"))
                        .toList();
        Assertions.assertThat(lines).hasSize(1);
        var entry = JsonMapper.builder().build().readTree(lines.getFirst());
        Assertions.assertThat(entry.get("correlation_id").asString()).isEqualTo(id);
        Assertions.assertThat(entry.get("operation").asString()).isEqualTo("http_request");
        Assertions.assertThat(entry.get("outcome").asString()).isEqualTo("completed");
        Assertions.assertThat(entry.get("status").asInt()).isEqualTo(200);
        Assertions.assertThat(entry.get("duration_ms").asLong()).isNotNegative();
        Assertions.assertThat(output.getOut()).doesNotContain("sentinel-private");
        Assertions.assertThat(MDC.get("correlation_id")).isNull();
    }
}
