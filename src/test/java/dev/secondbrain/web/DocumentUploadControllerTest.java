package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.secondbrain.documents.DocumentAcceptanceService;
import dev.secondbrain.documents.ExtractionState;
import dev.secondbrain.documents.UploadRejectedException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionSystemException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@WebMvcTest(DocumentUploadController.class)
@ExtendWith(OutputCaptureExtension.class)
class DocumentUploadControllerTest {
    private static final byte[] CONTENT =
            "sentinel-private-content".getBytes(StandardCharsets.UTF_8);

    @MockitoBean private DocumentAcceptanceService acceptanceService;

    @Autowired private MockMvc mvc;

    @Test
    void acceptsTheFileAndCorrelationAndClosesItsStream(CapturedOutput output) throws Exception {
        var correlationId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var closed = new AtomicBoolean();
        var input =
                new ByteArrayInputStream(CONTENT) {
                    @Override
                    public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                };
        var file =
                new MockMultipartFile(
                        "file", "sentinel-private-name.md", "text/markdown", CONTENT) {
                    @Override
                    public InputStream getInputStream() {
                        return input;
                    }
                };
        when(acceptanceService.accept(
                        eq("sentinel-private-name.md"),
                        eq((long) CONTENT.length),
                        any(InputStream.class),
                        eq(correlationId)))
                .thenAnswer(
                        invocation -> {
                            assertThat(((InputStream) invocation.getArgument(2)).readAllBytes())
                                    .containsExactly(CONTENT);
                            assertThat(closed).isFalse();
                            return new DocumentAcceptanceService.Receipt(
                                    documentId, ExtractionState.QUEUED);
                        });

        mvc.perform(multipart("/documents").file(file).header("X-Correlation-ID", correlationId))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Correlation-ID", correlationId.toString()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.aMapWithSize(2)));
        assertThat(closed).isTrue();
        assertThat(output.getOut()).doesNotContain("sentinel-private");
    }

    @Test
    void generatesCorrelationWhenTheHeaderIsMissing() throws Exception {
        var documentId = UUID.randomUUID();
        var captured = new java.util.concurrent.atomic.AtomicReference<UUID>();
        when(acceptanceService.accept(any(), anyLong(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            captured.set(invocation.getArgument(3));
                            return new DocumentAcceptanceService.Receipt(
                                    documentId, ExtractionState.QUEUED);
                        });
        var response =
                mvc.perform(multipart("/documents").file(file()))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse();
        assertThat(captured.get()).isNotNull();
        assertThat(response.getHeader("X-Correlation-ID")).isEqualTo(captured.get().toString());
    }

    @Test
    void requiresOneNamedFilePartAndMultipartContent() throws Exception {
        mvc.perform(multipart("/documents"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_FILE"));
        mvc.perform(multipart("/documents").file(file()).file(file()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SINGLE_FILE_REQUIRED"));
        mvc.perform(post("/documents").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(acceptanceService);
    }

    @Test
    void mapsEachFixedValidationReasonWithoutEchoingInput(CapturedOutput output) throws Exception {
        for (var reason : UploadRejectedException.Reason.values()) {
            doThrow(new UploadRejectedException(reason))
                    .when(acceptanceService)
                    .accept(any(), anyLong(), any(), any());
            mvc.perform(multipart("/documents").file(file()))
                    .andExpect(
                            status().is(
                                            reason == UploadRejectedException.Reason.FILE_TOO_LARGE
                                                    ? 413
                                                    : 400))
                    .andExpect(jsonPath("$.code").value(reason.name()))
                    .andExpect(jsonPath("$.message").value(reason.message()));
        }
        assertThat(output.getOut()).doesNotContain("sentinel-private");
    }

    @Test
    void hidesStorageDatabaseAndCommitFailures(CapturedOutput output) throws Exception {
        for (var failure :
                new Exception[] {
                    new IOException("sentinel-private-storage"),
                    new DataAccessResourceFailureException("sentinel-private-database"),
                    new TransactionSystemException("sentinel-private-commit")
                }) {
            doThrow(failure).when(acceptanceService).accept(any(), anyLong(), any(), any());
            var response =
                    mvc.perform(multipart("/documents").file(file()))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.code").value("UPLOAD_UNAVAILABLE"))
                            .andReturn()
                            .getResponse();
            assertThat(response.getContentAsString()).doesNotContain("sentinel-private");
        }
        assertThat(output.getOut()).doesNotContain("sentinel-private");
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "sentinel-private-name.txt", "text/plain", CONTENT);
    }
}
