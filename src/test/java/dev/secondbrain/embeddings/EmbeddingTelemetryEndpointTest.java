package dev.secondbrain.embeddings;

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

import java.util.OptionalLong;

@SpringBootTest(
        classes = EmbeddingTelemetryEndpointTest.TelemetryApplication.class,
        properties = {
            "spring.config.import=",
            "management.endpoint.health.group.readiness.include=readinessState"
        })
@AutoConfigureMockMvc
class EmbeddingTelemetryEndpointTest {
    @Autowired private EmbeddingTelemetry telemetry;
    @Autowired private MockMvc mvc;

    @Test
    void exposesOnlyFixedOutcomeCountsDurationsAndReportedUsage() throws Exception {
        try (var success = telemetry.start()) {
            success.success(OptionalLong.of(23));
        }
        try (var quota = telemetry.start()) {
            quota.failure(EmbeddingFailure.Reason.QUOTA_EXHAUSTED);
        }
        for (var name :
                new String[] {
                    "secondbrain.embedding.requests", "secondbrain.embedding.request.duration"
                }) {
            mvc.perform(
                            get("/actuator/metrics/" + name)
                                    .queryParam("tag", "outcome:quota_exhausted"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.measurements[0].statistic").value("COUNT"))
                    .andExpect(jsonPath("$.measurements[0].value").value(1.0));
        }
        mvc.perform(get("/actuator/metrics/secondbrain.embedding.request.duration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUnit").value("seconds"));
        mvc.perform(get("/actuator/metrics/secondbrain.embedding.tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(23.0))
                .andExpect(jsonPath("$.availableTags").isEmpty());
        mvc.perform(get("/actuator/metrics/secondbrain.embedding.usage.reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));
    }

    @EnableAutoConfiguration(
            exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    static class TelemetryApplication {
        @Bean
        EmbeddingTelemetry telemetry(MeterRegistry meters, ObservationRegistry observations) {
            return new EmbeddingTelemetry(meters, observations);
        }
    }
}
