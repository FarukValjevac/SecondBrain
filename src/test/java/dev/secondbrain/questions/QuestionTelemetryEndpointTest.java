package dev.secondbrain.questions;

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

import java.util.List;
import java.util.OptionalLong;

@SpringBootTest(
        classes = QuestionTelemetryEndpointTest.TelemetryApplication.class,
        properties = {
            "spring.config.import=",
            "management.endpoint.health.group.readiness.include=readinessState"
        })
@AutoConfigureMockMvc
class QuestionTelemetryEndpointTest {
    @Autowired private QuestionTelemetry telemetry;
    @Autowired private MockMvc mvc;

    @Test
    void exposesQuestionProviderDurationAndPerKindUsageWithoutPrivateLabels() throws Exception {
        try (var request = telemetry.startQuestion()) {
            request.outcome(QuestionTelemetry.Outcome.NO_EVIDENCE);
        }
        try (var provider = telemetry.startProvider()) {
            provider.candidate(ChatProvider.Outcome.INSUFFICIENT_INFORMATION);
            provider.usage(OptionalLong.of(19), OptionalLong.of(7), OptionalLong.of(26));
        }
        try (var quota = telemetry.startProvider()) {
            quota.failure(ChatFailure.Reason.QUOTA_EXHAUSTED);
        }
        for (var name : List.of("secondbrain.chat.requests", "secondbrain.chat.request.duration")) {
            mvc.perform(
                            get("/actuator/metrics/" + name)
                                    .queryParam("tag", "outcome:quota_exhausted"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.measurements[0].statistic").value("COUNT"))
                    .andExpect(jsonPath("$.measurements[0].value").value(1.0));
        }
        mvc.perform(
                        get("/actuator/metrics/secondbrain.question.requests")
                                .queryParam("tag", "outcome:no_evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));
        for (var name :
                List.of("secondbrain.question.duration", "secondbrain.chat.request.duration"))
            mvc.perform(get("/actuator/metrics/" + name))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.baseUnit").value("seconds"));
        mvc.perform(
                        get("/actuator/metrics/secondbrain.chat.tokens")
                                .queryParam("tag", "kind:total"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(26.0))
                .andExpect(jsonPath("$.availableTags").isEmpty());
        mvc.perform(
                        get("/actuator/metrics/secondbrain.chat.usage.reports")
                                .queryParam("tag", "kind:output"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));
    }

    @EnableAutoConfiguration(
            exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    static class TelemetryApplication {
        @Bean
        QuestionTelemetry telemetry(MeterRegistry meters, ObservationRegistry observations) {
            return new QuestionTelemetry(meters, observations);
        }
    }
}
