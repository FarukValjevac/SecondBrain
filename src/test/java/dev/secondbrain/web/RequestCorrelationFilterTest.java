package dev.secondbrain.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import jakarta.servlet.ServletException;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

class RequestCorrelationFilterTest {
    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestCorrelationFilter.class);
    private final ListAppender<ILoggingEvent> logs =
            new ListAppender<>() {
                @Override
                protected void append(ILoggingEvent event) {
                    event.prepareForDeferredProcessing();
                    super.append(event);
                }
            };
    private Map<String, String> previousContext;

    @BeforeEach
    void captureLogs() {
        previousContext = MDC.getCopyOfContextMap();
        MDC.clear();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void restoreLoggingContext() {
        logger.detachAppender(logs);
        logs.stop();
        if (previousContext == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousContext);
        }
    }

    @Test
    void generatesDistinctIdsAndClearsThemBetweenRequestsOnTheSameThread() throws Exception {
        var first = new MockHttpServletResponse();
        var second = new MockHttpServletResponse();
        for (var response : new MockHttpServletResponse[] {first, second}) {
            var request = new MockHttpServletRequest();
            filter.doFilter(
                    request,
                    response,
                    (incoming, outgoing) -> {
                        String id = response.getHeader(RequestCorrelationFilter.HEADER);
                        Assertions.assertThat(UUID.fromString(id).toString()).isEqualTo(id);
                        Assertions.assertThat(
                                        request.getAttribute(RequestCorrelationFilter.ATTRIBUTE))
                                .isEqualTo(UUID.fromString(id));
                        Assertions.assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY))
                                .isEqualTo(id);
                    });
            Assertions.assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
        }
        Assertions.assertThat(first.getHeader(RequestCorrelationFilter.HEADER))
                .isNotEqualTo(second.getHeader(RequestCorrelationFilter.HEADER));
        Assertions.assertThat(logs.list).hasSize(2);
    }

    @Test
    void reusesCanonicalIdAndRestoresOuterContext() throws Exception {
        String id = UUID.randomUUID().toString();
        MDC.put(RequestCorrelationFilter.MDC_KEY, "outer-scope");
        MDC.put("unrelated", "preserved");
        var request = new MockHttpServletRequest();
        request.addHeader(RequestCorrelationFilter.HEADER, id);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (incoming, outgoing) -> response.setStatus(404));

        Assertions.assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).isEqualTo(id);
        Assertions.assertThat(logs.list.getFirst().getMDCPropertyMap())
                .containsEntry(RequestCorrelationFilter.MDC_KEY, id);
        Assertions.assertThat(logs.list.getFirst().getKeyValuePairs().toString())
                .contains("status=\"404\"", "outcome=\"completed\"");
        Assertions.assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isEqualTo("outer-scope");
        Assertions.assertThat(MDC.get("unrelated")).isEqualTo("preserved");
    }

    @Test
    void replacesMalformedAndRepeatedHeadersWithFreshIds() throws Exception {
        String id = "a12ec456-7890-4abc-8def-123456789012";
        for (String[] supplied :
                new String[][] {
                    {"sentinel-private-header"},
                    {"1-1-1-1-1"},
                    {id.toUpperCase(java.util.Locale.ROOT)},
                    {"x".repeat(1000)},
                    {id, id}
                }) {
            var request = new MockHttpServletRequest();
            request.addHeader(RequestCorrelationFilter.HEADER, supplied);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (incoming, outgoing) -> {});
            String generated = response.getHeader(RequestCorrelationFilter.HEADER);
            Assertions.assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
            Assertions.assertThat(supplied).doesNotContain(generated);
            Assertions.assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
        }
        for (var event : logs.list) {
            Assertions.assertThat(
                            event.getFormattedMessage()
                                    + event.getKeyValuePairs()
                                    + event.getMDCPropertyMap())
                    .doesNotContain("sentinel-private-header");
        }
    }

    @Test
    void propagatesFailureWithSafeLogAndRestoresContext() {
        var request = new MockHttpServletRequest("POST", "/sentinel-private-filename");
        request.setQueryString("q=sentinel-private-query");
        request.addHeader("Cookie", "session=sentinel-private-cookie");
        request.setContent("sentinel-private-body".getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        var failure = new ServletException("sentinel-private-error");
        MDC.put(RequestCorrelationFilter.MDC_KEY, "outer-scope");

        Assertions.assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        request,
                                        response,
                                        (incoming, outgoing) -> {
                                            throw failure;
                                        }))
                .isSameAs(failure);

        var event = logs.list.getFirst();
        Assertions.assertThat(event.getFormattedMessage()).isEqualTo("HTTP request finished");
        Assertions.assertThat(event.getThrowableProxy()).isNull();
        Assertions.assertThat(event.getKeyValuePairs().toString())
                .contains("outcome=\"failed\"", "code=\"REQUEST_FAILED\"")
                .doesNotContain("status", "sentinel-private-error");
        Assertions.assertThat(event.getMDCPropertyMap())
                .containsEntry(
                        RequestCorrelationFilter.MDC_KEY,
                        response.getHeader(RequestCorrelationFilter.HEADER));
        Assertions.assertThat(
                        event.getFormattedMessage()
                                + event.getKeyValuePairs()
                                + event.getMDCPropertyMap())
                .doesNotContain("sentinel-private");
        Assertions.assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isEqualTo("outer-scope");
    }
}
