package dev.secondbrain.imports;

import dev.secondbrain.imports.PageImportException.Reason;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Fetches one web page safely. The host is resolved once, every address is checked, and the
 * connection goes to that checked address, so DNS rebinding cannot redirect it. Redirects are
 * followed by hand and checked again. No cookies, credentials or compression are used.
 */
final class PageFetcher {
    static final int MAXIMUM_REDIRECTS = 3;
    private static final int MAXIMUM_HEADER_BYTES = 16 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(20);
    private final AddressGuard guard;
    private final Resolver resolver;
    private final long maximumBytes;
    private final Clock clock;

    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** A fetched page, already decoded to UTF-8. */
    record Page(URI finalUri, String mediaType, byte[] utf8, Instant fetchedAt) {}

    PageFetcher(AddressGuard guard, Resolver resolver, long maximumBytes, Clock clock) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumBytes < 1) throw new IllegalArgumentException("Maximum size must be positive");
        this.maximumBytes = maximumBytes;
    }

    Page fetch(String address) throws InterruptedException {
        var uri = parse(address);
        var deadline = clock.instant().plus(TOTAL_TIMEOUT);
        for (int hop = 0; hop <= MAXIMUM_REDIRECTS; hop++) {
            var response = request(uri, deadline);
            if (response.status() >= 300 && response.status() < 400) {
                var location = response.headers().get("location");
                if (location == null) throw new PageImportException(Reason.HTTP_ERROR);
                try {
                    uri = parse(uri.resolve(location.strip()).toString());
                } catch (IllegalArgumentException invalid) {
                    throw new PageImportException(Reason.INVALID_URL, invalid);
                }
                continue;
            }
            if (response.status() != 200) throw new PageImportException(Reason.HTTP_ERROR);
            return page(uri, response);
        }
        throw new PageImportException(Reason.TOO_MANY_REDIRECTS);
    }

    static URI parse(String address) {
        try {
            var uri = new URI(address.strip()).normalize();
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https"))
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || address.length() > 2048) throw new PageImportException(Reason.INVALID_URL);
            return uri;
        } catch (java.net.URISyntaxException invalid) {
            throw new PageImportException(Reason.INVALID_URL, invalid);
        }
    }

    private Response request(URI uri, Instant deadline) throws InterruptedException {
        var host = uri.getHost();
        final InetAddress[] addresses;
        try {
            addresses =
                    resolver.resolve(
                            host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
        } catch (UnknownHostException unknown) {
            throw new PageImportException(Reason.UNREACHABLE, unknown);
        }
        // Every address must be allowed: a mixed answer is a common rebinding trick.
        if (addresses.length == 0) throw new PageImportException(Reason.UNREACHABLE);
        for (var address : addresses)
            if (!guard.allowed(address)) throw new PageImportException(Reason.BLOCKED_ADDRESS);
        boolean https = uri.getScheme().equalsIgnoreCase("https");
        int port = uri.getPort() == -1 ? (https ? 443 : 80) : uri.getPort();
        IOException last = null;
        for (var address : addresses) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("Page import interrupted");
            try (var socket = connect(address, port, host, https, deadline)) {
                return exchange(socket, uri, host, port, https, deadline);
            } catch (SocketTimeoutException slow) {
                throw new PageImportException(Reason.TIMED_OUT, slow);
            } catch (IOException unavailable) {
                last = unavailable;
            }
        }
        throw new PageImportException(Reason.UNREACHABLE, last);
    }

    private Socket connect(
            InetAddress address, int port, String host, boolean https, Instant deadline)
            throws IOException {
        var plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(address, port), (int) CONNECT_TIMEOUT.toMillis());
            plain.setSoTimeout(
                    (int) Math.max(1, Duration.between(clock.instant(), deadline).toMillis()));
            if (!https) return plain;
            var tls =
                    (SSLSocket)
                            ((SSLSocketFactory) SSLSocketFactory.getDefault())
                                    .createSocket(plain, host, port, true);
            var parameters = tls.getSSLParameters();
            // Verify the certificate for the requested host name, not the address.
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(List.of(new SNIHostName(host)));
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            return tls;
        } catch (IOException | RuntimeException failure) {
            plain.close();
            throw failure;
        }
    }

    private Response exchange(
            Socket socket, URI uri, String host, int port, boolean https, Instant deadline)
            throws IOException {
        var path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        var hostHeader = (https ? port == 443 : port == 80) ? host : host + ":" + port;
        OutputStream out = socket.getOutputStream();
        out.write(
                ("GET "
                                + path
                                + " HTTP/1.1\r\nHost: "
                                + hostHeader
                                + "\r\nUser-Agent: SecondBrain\r\nAccept: text/html, text/plain;q=0.9"
                                + "\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        var in = new BufferedInputStream(socket.getInputStream());
        var statusLine = line(in);
        var parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1."))
            throw new IOException("Not an HTTP response");
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid status", invalid);
        }
        var headers = new TreeMap<String, String>();
        int headerBytes = statusLine.length();
        while (true) {
            var header = line(in);
            headerBytes += header.length();
            if (headerBytes > MAXIMUM_HEADER_BYTES) throw new IOException("Headers too large");
            if (header.isEmpty()) break;
            int colon = header.indexOf(':');
            if (colon > 0)
                headers.put(
                        header.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                        header.substring(colon + 1).strip());
        }
        if (status != 200) return new Response(status, headers, new byte[0]);
        try {
            return new Response(status, headers, body(in, headers, deadline));
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid body length", invalid);
        }
    }

    private byte[] body(InputStream in, Map<String, String> headers, Instant deadline)
            throws IOException {
        var length = headers.get("content-length");
        if (length != null && Long.parseLong(length.strip()) > maximumBytes)
            throw new PageImportException(Reason.TOO_LARGE);
        var out = new ByteArrayOutputStream();
        if ("chunked".equalsIgnoreCase(headers.getOrDefault("transfer-encoding", "").strip())) {
            while (true) {
                var size = line(in).split(";", 2)[0].strip();
                long chunk = Long.parseLong(size, 16);
                if (chunk == 0) break;
                copy(in, out, chunk, deadline);
                line(in);
            }
        } else if (length != null) {
            copy(in, out, Long.parseLong(length.strip()), deadline);
        } else {
            copy(in, out, Long.MAX_VALUE, deadline);
        }
        return out.toByteArray();
    }

    private void copy(InputStream in, ByteArrayOutputStream out, long bytes, Instant deadline)
            throws IOException {
        var buffer = new byte[8192];
        long remaining = bytes;
        while (remaining > 0) {
            if (clock.instant().isAfter(deadline)) throw new SocketTimeoutException("Deadline");
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                if (bytes == Long.MAX_VALUE) return;
                throw new IOException("Body ended early");
            }
            if (out.size() + read > maximumBytes) throw new PageImportException(Reason.TOO_LARGE);
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static String line(InputStream in) throws IOException {
        var line = new ByteArrayOutputStream();
        while (true) {
            int value = in.read();
            if (value < 0) throw new IOException("Connection closed");
            if (value == '\n') break;
            if (value != '\r') line.write(value);
            if (line.size() > MAXIMUM_HEADER_BYTES) throw new IOException("Line too long");
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    private Page page(URI uri, Response response) {
        var contentType =
                response.headers().getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
        var mediaType = contentType.split(";", 2)[0].strip();
        if (!List.of("text/html", "application/xhtml+xml", "text/plain", "text/markdown")
                .contains(mediaType)) throw new PageImportException(Reason.UNSUPPORTED_CONTENT);
        var charset = StandardCharsets.UTF_8;
        for (var parameter : contentType.split(";")) {
            var pair = parameter.strip().split("=", 2);
            if (pair.length == 2 && pair[0].equals("charset")) {
                try {
                    charset = Charset.forName(pair[1].replace("\"", "").strip());
                } catch (IllegalArgumentException unsupported) {
                    throw new PageImportException(Reason.UNSUPPORTED_CONTENT, unsupported);
                }
            }
        }
        // The extractors read UTF-8; other declared encodings are converted once here.
        var utf8 =
                charset.equals(StandardCharsets.UTF_8)
                        ? response.body()
                        : new String(response.body(), charset).getBytes(StandardCharsets.UTF_8);
        if (utf8.length > maximumBytes) throw new PageImportException(Reason.TOO_LARGE);
        return new Page(uri, mediaType, utf8, clock.instant());
    }

    private record Response(int status, Map<String, String> headers, byte[] body) {}
}
