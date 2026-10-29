package dev.secondbrain.search;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = SearchTelemetryEndpointTest.TelemetryApplication.class,
        properties = {
            "spring.config.import=",
            "management.endpoint.health.group.readiness.include=readinessState"
        })
@AutoConfigureMockMvc
class SearchTelemetryEndpointTest {
    @Autowired private SearchTelemetry telemetry;
    @Autowired private MockMvc mvc;

    @Test
    void exposesSafeRequestDurationAndFinalResultMeasurements() throws Exception {
        try (var operation = telemetry.start()) {
            operation.success(3);
        }
        try (var operation = telemetry.start()) {
            operation.success(0);
        }
        try (var operation = telemetry.start()) {
            operation.failure(SearchFailure.Reason.QUOTA_EXHAUSTED);
        }
        for (var metric :
                new String[] {"secondbrain.search.requests", "secondbrain.search.duration"}) {
            mvc.perform(
                            get("/actuator/metrics/" + metric)
                                    .queryParam("tag", "outcome:quota_exhausted"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.measurements[0].statistic").value("COUNT"))
                    .andExpect(jsonPath("$.measurements[0].value").value(1.0));
        }
        mvc.perform(get("/actuator/metrics/secondbrain.search.duration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUnit").value("seconds"));
        mvc.perform(get("/actuator/metrics/secondbrain.search.results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUnit").value("passages"))
                .andExpect(jsonPath("$.availableTags").isEmpty())
                .andExpect(
                        jsonPath("$.measurements[?(@.statistic == 'COUNT')].value")
                                .value(contains(2.0)))
                .andExpect(
                        jsonPath("$.measurements[?(@.statistic == 'TOTAL')].value")
                                .value(contains(3.0)));
    }

    @EnableAutoConfiguration(
            exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    static class TelemetryApplication {
        @Bean
        SearchTelemetry searchTelemetry(MeterRegistry meters, ObservationRegistry observations) {
            return new SearchTelemetry(meters, observations);
        }
    }
}
