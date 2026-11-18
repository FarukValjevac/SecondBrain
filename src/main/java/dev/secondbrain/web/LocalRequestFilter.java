package dev.secondbrain.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * A loopback listener is still reachable from the user's own browser. Requests must name this
 * machine, and state-changing requests must not come from another site.
 */
@Component
final class LocalRequestFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(LocalRequestFilter.class);
    // A rebound attacker domain keeps its own name in Host, whatever address it resolves to.
    private static final Set<String> LOCAL_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]", "::1");
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getServerName();
        if (host == null || !LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            reject(response, "FOREIGN_HOST");
            return;
        }
        if (!READ_METHODS.contains(request.getMethod()) && foreignSource(request)) {
            reject(response, "FOREIGN_ORIGIN");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Non-browser clients such as curl send neither header and remain supported. */
    private static boolean foreignSource(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        // "none" is a user-initiated navigation; another local port is same-site, not same-origin.
        if (site != null && !site.equals("same-origin") && !site.equals("none")) return true;
        String origin = request.getHeader("Origin");
        if (origin == null) return false;
        String own =
                request.getScheme()
                        + "://"
                        + request.getServerName()
                        + ":"
                        + request.getServerPort();
        String ownDefaultPort = request.getScheme() + "://" + request.getServerName();
        boolean defaultPort =
                request.getServerPort() == ("https".equals(request.getScheme()) ? 443 : 80);
        return !(origin.equalsIgnoreCase(own)
                || (defaultPort && origin.equalsIgnoreCase(ownDefaultPort)));
    }

    private static void reject(HttpServletResponse response, String code) throws IOException {
        // Fixed text only: never echo or log the supplied Host or Origin.
        LOG.atWarn()
                .addKeyValue("operation", "http_request")
                .addKeyValue("outcome", "rejected")
                .addKeyValue("code", code)
                .log("HTTP request rejected");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("This request did not come from the local SecondBrain page.");
    }
}
