package dev.secondbrain.imports;

import static dev.secondbrain.TestHttp.client;
import static dev.secondbrain.TestHttp.encode;
import static dev.secondbrain.TestHttp.get;
import static dev.secondbrain.TestHttp.origin;
import static dev.secondbrain.TestHttp.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;

import dev.secondbrain.TestDatabase;
import dev.secondbrain.documents.DocumentAcceptanceService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Real application: the production guard refuses local pages; an allowed import is stored. */
@ExtendWith(OutputCaptureExtension.class)
class WebImportIT {
    @TempDir private Path storage;

    @Test
    void refusesLocalPagesAndStoresAnAllowedPageWithItsSource(CapturedOutput output)
            throws Exception {
        var requests = new AtomicInteger();
        var stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext(
                "/guide",
                exchange -> {
                    requests.incrementAndGet();
                    var body =
                            "<html><body><h1>Private guide</h1><p>Lists keep order.</p></body></html>"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        stub.start();
        var url = "http://127.0.0.1:" + stub.getAddress().getPort() + "/guide";
        try (var postgres = TestDatabase.create();
                var context = start(postgres);
                var client = client()) {
            var origin = origin(context);
            var refused = post(client, origin, "/documents/import", "url=" + encode(url));
            assertThat(refused.statusCode()).isEqualTo(303);
            var library = get(client, origin, "/library").body();
            assertThat(requests).hasValue(0);
            assertThat(documents(context)).isZero();

            // The same import with a guard that allows the local stub shows the stored result.
            var allowed =
                    new WebPageImportService(
                            new PageFetcher(
                                    address -> address.isLoopbackAddress(),
                                    InetAddress::getAllByName,
                                    1024 * 1024,
                                    Clock.systemUTC()),
                            context.getBean(DocumentAcceptanceService.class),
                            context.getBean(JdbcClient.class));
            var receipt = allowed.importPage(url, UUID.randomUUID());
            assertThat(requests).hasValue(1);
            assertThat(allowed.source(receipt.documentId()))
                    .hasValueSatisfying(source -> assertThat(source.url()).isEqualTo(url));
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () ->
                                    assertThat(
                                                    get(
                                                                    client,
                                                                    origin,
                                                                    "/documents/"
                                                                            + receipt.documentId())
                                                            .body())
                                            .contains("Imported from", url, "Lists keep order."));

            // The source is saved with the document: if it cannot be saved, neither is kept.
            var jdbc = context.getBean(JdbcClient.class);
            jdbc.sql("ALTER TABLE document_sources RENAME TO document_sources_unavailable")
                    .update();
            int before = documents(context);
            assertThatThrownBy(() -> allowed.importPage(url, UUID.randomUUID()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(documents(context)).isEqualTo(before);
            jdbc.sql("ALTER TABLE document_sources_unavailable RENAME TO document_sources")
                    .update();
        } finally {
            stub.stop(0);
        }
        assertThat(output).doesNotContain(url, "Private guide");
    }

    private static int documents(ConfigurableApplicationContext context) {
        return context.getBean(JdbcClient.class)
                .sql("SELECT count(*) FROM documents")
                .query(Integer.class)
                .single();
    }

    private ConfigurableApplicationContext start(TestDatabase postgres) {
        return dev.secondbrain.TestApplication.run(
                postgres.arguments(
                        "--secondbrain.telemetry.otlp-endpoint=",
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--secondbrain.events.polling-enabled=true",
                        "--secondbrain.events.poll-interval=50ms",
                        "--secondbrain.documents.storage-directory=" + storage));
    }
}
