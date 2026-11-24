package dev.secondbrain.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.secondbrain.imports.PageImportException.Reason;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback stub stands in for the internet. The test guard allows loopback only so the stub is
 * reachable; every other internal range still uses the production rules.
 */
class PageFetcherTest {
    private HttpServer server;
    private int port;
    private final AtomicInteger resolutions = new AtomicInteger();
    private final AddressGuard guard =
            address -> address.isLoopbackAddress() || AddressGuard.isPublic(address);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();
        server.createContext(
                "/page",
                exchange -> reply(exchange, 200, "text/html; charset=utf-8", "<p>Hello 📚</p>"));
        server.createContext(
                "/latin",
                exchange -> reply(exchange, 200, "text/plain; charset=ISO-8859-1", "café"));
        server.createContext(
                "/meta",
                exchange ->
                        reply(
                                exchange,
                                200,
                                "text/html",
                                "<html><head><meta charset=\"ISO-8859-1\"></head><p>Grüße</p>",
                                StandardCharsets.ISO_8859_1));
        server.createContext(
                "/http-equiv",
                exchange ->
                        reply(
                                exchange,
                                200,
                                "text/html",
                                "<meta http-equiv=\"Content-Type\" content=\"text/html;"
                                        + " charset=windows-1252\"><p>Köln</p>",
                                StandardCharsets.ISO_8859_1));
        server.createContext(
                "/unlabeled",
                exchange ->
                        reply(
                                exchange,
                                200,
                                "text/html",
                                "<p>Straße</p>",
                                StandardCharsets.ISO_8859_1));
        server.createContext("/image", exchange -> reply(exchange, 200, "image/png", "png"));
        server.createContext("/missing", exchange -> reply(exchange, 404, "text/html", "no"));
        server.createContext(
                "/big", exchange -> reply(exchange, 200, "text/plain", "x".repeat(200)));
        server.createContext(
                "/chunked",
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(200, 0);
                    try (var body = exchange.getResponseBody()) {
                        for (int i = 0; i < 10; i++) body.write("x".repeat(30).getBytes());
                    }
                });
        server.createContext(
                "/to-internal", exchange -> redirect(exchange, "http://internal.test/"));
        server.createContext("/to-page", exchange -> redirect(exchange, "/page"));
        server.createContext("/loop", exchange -> redirect(exchange, "/loop"));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void fetchesAPageAndConvertsItsDeclaredEncodingToUtf8() throws Exception {
        var page = fetcher(1024).fetch("http://site.test:" + port + "/page");
        assertThat(page.mediaType()).isEqualTo("text/html");
        assertThat(new String(page.utf8(), StandardCharsets.UTF_8)).isEqualTo("<p>Hello 📚</p>");
        var latin = fetcher(1024).fetch("http://site.test:" + port + "/latin");
        assertThat(new String(latin.utf8(), StandardCharsets.UTF_8)).isEqualTo("café");
    }

    @Test
    void usesTheCharsetAPageDeclaresInItsOwnMarkup() throws Exception {
        assertThat(text("/meta")).endsWith("<p>Grüße</p>");
        assertThat(text("/http-equiv")).endsWith("<p>Köln</p>");
        // No declaration and not valid UTF-8: read like a browser, as windows-1252.
        assertThat(text("/unlabeled")).isEqualTo("<p>Straße</p>");
        // Valid UTF-8 without a label stays UTF-8.
        assertThat(PageFetcher.undeclared("text/html", "Grüße".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(StandardCharsets.UTF_8);
    }

    private String text(String path) throws Exception {
        return new String(
                fetcher(1024).fetch("http://site.test:" + port + path).utf8(),
                StandardCharsets.UTF_8);
    }

    @Test
    void followsSafeRedirectsAndChecksEveryHop() throws Exception {
        assertThat(
                        fetcher(1024)
                                .fetch("http://site.test:" + port + "/to-page")
                                .finalUri()
                                .getPath())
                .isEqualTo("/page");
        assertFailure("http://site.test:" + port + "/to-internal", Reason.BLOCKED_ADDRESS);
        assertFailure("http://site.test:" + port + "/loop", Reason.TOO_MANY_REDIRECTS);
    }

    @Test
    void resolvesEachHostOnceAndConnectsToThatAddress() throws Exception {
        fetcher(1024).fetch("http://site.test:" + port + "/page");
        // One lookup per request: a later, different DNS answer cannot change the target.
        assertThat(resolutions).hasValue(1);
    }

    @Test
    void refusesHostsWithAnyInternalAddress() {
        assertFailure("http://internal.test:" + port + "/page", Reason.BLOCKED_ADDRESS);
        assertFailure("http://mixed.test:" + port + "/page", Reason.BLOCKED_ADDRESS);
        assertFailure("http://169.254.169.254/latest/meta-data/", Reason.BLOCKED_ADDRESS);
    }

    @Test
    void enforcesContentTypeStatusAndSizeLimits() {
        assertFailure("http://site.test:" + port + "/image", Reason.UNSUPPORTED_CONTENT);
        assertFailure("http://site.test:" + port + "/missing", Reason.HTTP_ERROR);
        assertThatThrownBy(() -> fetcher(100).fetch("http://site.test:" + port + "/big"))
                .isInstanceOfSatisfying(
                        PageImportException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.TOO_LARGE));
        assertThatThrownBy(() -> fetcher(100).fetch("http://site.test:" + port + "/chunked"))
                .isInstanceOfSatisfying(
                        PageImportException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.TOO_LARGE));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ftp://site.test/file",
                "http://user:pass@site.test/",
                "not a url",
                "http:///x"
            })
    void rejectsAddressesThatAreNotPlainWebUrls(String address) {
        assertFailure(address, Reason.INVALID_URL);
    }

    private void assertFailure(String address, Reason reason) {
        assertThatThrownBy(() -> fetcher(1024).fetch(address))
                .isInstanceOfSatisfying(
                        PageImportException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason))
                .hasMessageNotContaining("site.test");
    }

    private PageFetcher fetcher(long maximumBytes) {
        var hosts =
                Map.of(
                        "site.test", new String[] {"127.0.0.1"},
                        "internal.test", new String[] {"10.0.0.5"},
                        "mixed.test", new String[] {"127.0.0.1", "10.0.0.5"});
        return new PageFetcher(
                guard,
                host -> {
                    resolutions.incrementAndGet();
                    var names = hosts.get(host);
                    if (names == null) return InetAddress.getAllByName(host);
                    var addresses = new InetAddress[names.length];
                    for (int i = 0; i < names.length; i++)
                        addresses[i] = InetAddress.getByName(names[i]);
                    return addresses;
                },
                maximumBytes,
                Clock.systemUTC());
    }

    private static void reply(HttpExchange exchange, int status, String type, String body)
            throws IOException {
        reply(
                exchange,
                status,
                type,
                body,
                type.contains("ISO-8859-1") ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
    }

    private static void reply(
            HttpExchange exchange,
            int status,
            String type,
            String body,
            java.nio.charset.Charset encoding)
            throws IOException {
        var bytes = body.getBytes(encoding);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }
}
