package dev.secondbrain.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/** Correlates synchronous HTTP handling without recording request content. */
@Component
final class RequestCorrelationFilter extends OncePerRequestFilter {
    static final String HEADER = "X-Correlation-ID";
    static final String ATTRIBUTE = "dev.secondbrain.web.RequestCorrelationFilter.correlationId";
    static final String MDC_KEY = "correlation_id";
    private static final Logger LOG = LoggerFactory.getLogger(RequestCorrelationFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = correlationId(request);
        request.setAttribute(ATTRIBUTE, UUID.fromString(correlationId));
        response.setHeader(HEADER, correlationId);
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, correlationId);
        long started = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            try {
                var entry =
                        LOG.atInfo()
                                .addKeyValue("operation", "http_request")
                                .addKeyValue(
                                        "duration_ms",
                                        Duration.ofNanos(System.nanoTime() - started).toMillis())
                                .addKeyValue("outcome", completed ? "completed" : "failed");
                if (completed) {
                    entry.addKeyValue("status", response.getStatus());
                } else {
                    // The container may map an escaping exception after this filter returns.
                    entry.addKeyValue("code", "REQUEST_FAILED");
                }
                entry.log("HTTP request finished");
            } finally {
                // Server threads are reused; preserve an outer scope without leaking this ID.
                if (previous == null) {
                    MDC.remove(MDC_KEY);
                } else {
                    MDC.put(MDC_KEY, previous);
                }
            }
        }
    }

    private String correlationId(HttpServletRequest request) {
        var values = request.getHeaders(HEADER);
        String supplied = values.hasMoreElements() ? values.nextElement() : null;
        if (supplied != null && !values.hasMoreElements() && supplied.length() == 36) {
            try {
                if (UUID.fromString(supplied).toString().equals(supplied)) {
                    return supplied;
                }
            } catch (IllegalArgumentException invalidId) {
                // Ignore invalid input without echoing or logging it.
            }
        }
        return UUID.randomUUID().toString();
    }
}
