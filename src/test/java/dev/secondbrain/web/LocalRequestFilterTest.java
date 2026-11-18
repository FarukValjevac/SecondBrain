package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalRequestFilterTest {
    private static final String PRIVATE_HOST = "private-host-sentinel.example";
    private final LocalRequestFilter filter = new LocalRequestFilter();

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "localhost", "LOCALHOST", "[::1]"})
    void localHostsReachTheApplicationForReadsAndHeaderlessWrites(String host) throws Exception {
        for (String method : new String[] {"GET", "POST"}) {
            var chain = new MockFilterChain();
            var response = run(request(method, host), chain);
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void reboundOrForeignHostNamesAreRejectedWithoutEchoingThem(String method) throws Exception {
        var chain = new MockFilterChain();
        var response = run(request(method, PRIVATE_HOST), chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).doesNotContain(PRIVATE_HOST);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "http://127.0.0.1:8080,same-origin,200",
        "http://127.0.0.1:8080,,200",
        ",none,200",
        "https://attacker.example,cross-site,403",
        "https://attacker.example,,403",
        // Another local application, such as a dashboard on a different port.
        "http://127.0.0.1:3000,same-site,403",
        "http://127.0.0.1:3000,,403",
        "http://localhost:8080,,403",
        "null,,403",
        // Search and question pages use Referrer-Policy: no-referrer, so browsers send null.
        "null,same-origin,200",
        "null,cross-site,403",
        ",cross-site,403",
        ",same-site,403"
    })
    void stateChangingRequestsMustComeFromThisOrigin(String origin, String site, int status)
            throws Exception {
        var request = request("POST", "127.0.0.1");
        if (origin != null) request.addHeader("Origin", origin);
        if (site != null) request.addHeader("Sec-Fetch-Site", site);
        var chain = new MockFilterChain();
        assertThat(run(request, chain).getStatus()).isEqualTo(status);
        assertThat(chain.getRequest() != null).isEqualTo(status == 200);
    }

    @Test
    void crossSiteReadsAreNotBlockedBecauseTheBrowserAlreadyHidesTheirResponses() throws Exception {
        var request = request("GET", "127.0.0.1");
        request.addHeader("Origin", "https://attacker.example");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        assertThat(run(request, new MockFilterChain()).getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest request(String method, String host) {
        var request = new MockHttpServletRequest(method, "/documents");
        request.setServerName(host);
        request.setServerPort(8080);
        return request;
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, MockFilterChain chain)
            throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
}
