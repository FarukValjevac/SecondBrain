package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.secondbrain.documents.DocumentAcceptanceService;
import dev.secondbrain.documents.ExtractionState;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/** Uses real servlet multipart parsing; MockMvc's multipart builder bypasses parser limits. */
class DocumentUploadHttpIT {
    private static ConfigurableApplicationContext context;
    private DocumentAcceptanceService acceptanceService;
    private int port;

    @BeforeAll
    static void startServer() {
        context =
                new SpringApplicationBuilder(HttpApplication.class)
                        .web(WebApplicationType.SERVLET)
                        .run(
                                "--spring.config.import=",
                                "--secondbrain.documents.max-upload-size=32B",
                                "--management.endpoint.health.group.readiness.include=readinessState",
                                "--server.address=127.0.0.1",
                                "--server.port=0");
    }

    @AfterAll
    static void stopServer() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void prepareService() {
        acceptanceService = context.getBean(DocumentAcceptanceService.class);
        reset(acceptanceService);
        port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    @Test
    void acceptsAFileAtTheConfiguredServletLimit() throws Exception {
        var documentId = UUID.randomUUID();
        when(acceptanceService.accept(any(), anyLong(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            assertThat((long) invocation.getArgument(1)).isEqualTo(32);
                            return new DocumentAcceptanceService.Receipt(
                                    documentId, ExtractionState.QUEUED);
                        });
        var response = sendMultipart("x".repeat(32));
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.headers().firstValue("X-Correlation-ID")).isPresent();
        assertThat(
                        JsonMapper.builder()
                                .build()
                                .readTree(response.body())
                                .get("documentId")
                                .asString())
                .isEqualTo(documentId.toString());
    }

    @Test
    void rejectsOversizedFilesBeforeCallingTheService() throws Exception {
        var response = sendMultipart("x".repeat(33));
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("FILE_TOO_LARGE").doesNotContain("sentinel-private");
        verifyNoInteractions(acceptanceService);
    }

    @Test
    void boundsTheCompleteMultipartRequest() throws Exception {
        var boundary = "secondbrain-boundary";
        var body =
                "--"
                        + boundary
                        + "\r\nContent-Disposition: form-data; name=\"extra\"\r\n\r\n"
                        + "x".repeat(1024 * 1024 + 128)
                        + "\r\n--"
                        + boundary
                        + "--\r\n";
        var response = send("multipart/form-data; boundary=" + boundary, body);
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("FILE_TOO_LARGE");
        verifyNoInteractions(acceptanceService);
    }

    @Test
    void hidesParserFailuresBeforeAControllerIsSelected() throws Exception {
        var response = send("multipart/form-data", "sentinel-private-broken-request");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("INVALID_UPLOAD").doesNotContain("sentinel-private");
        verifyNoInteractions(acceptanceService);
    }

    private HttpResponse<String> sendMultipart(String content) throws Exception {
        var boundary = "secondbrain-boundary";
        var body =
                "--"
                        + boundary
                        + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sentinel-private-name.txt\""
                        + "\r\nContent-Type: text/plain\r\n\r\n"
                        + content
                        + "\r\n--"
                        + boundary
                        + "--\r\n";
        return send("multipart/form-data; boundary=" + boundary, body);
    }

    private HttpResponse<String> send(String contentType, String body) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request =
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/documents"))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", contentType)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(
            exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({
        DocumentUploadController.class,
        DocumentUploadErrors.class,
        DocumentUploadConfiguration.class,
        RequestCorrelationFilter.class
    })
    static class HttpApplication {
        @Bean
        DocumentAcceptanceService acceptanceService() {
            return mock(DocumentAcceptanceService.class);
        }
    }
}
