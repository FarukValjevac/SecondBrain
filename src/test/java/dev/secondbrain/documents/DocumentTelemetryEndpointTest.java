package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

@SpringBootTest(
        classes = DocumentTelemetryEndpointTest.TelemetryApplication.class,
        properties = {
            "spring.config.import=",
            "management.endpoint.health.group.readiness.include=readinessState"
        })
@AutoConfigureMockMvc
class DocumentTelemetryEndpointTest {
    private final DocumentTelemetry telemetry;
    private final MeterRegistry meters;
    private final MockMvc mvc;

    @Autowired
    DocumentTelemetryEndpointTest(DocumentTelemetry telemetry, MeterRegistry meters, MockMvc mvc) {
        this.telemetry = telemetry;
        this.meters = meters;
        this.mvc = mvc;
    }

    @Test
    void exposesTheApplicationCountersAndBootObservationTimersThroughActuator() throws Exception {
        var correlationId = UUID.randomUUID();
        try (var upload = telemetry.upload(correlationId)) {
            upload.outcome(DocumentTelemetry.Outcome.ACCEPTED);
        }
        try (var cleanup = telemetry.cleanup()) {
            telemetry.deletedOriginal();
            cleanup.outcome(DocumentTelemetry.Outcome.COMPLETED);
        }
        for (var metric :
                new String[] {
                    "secondbrain.document.uploads", "secondbrain.document.upload.duration"
                }) {
            mvc.perform(get("/actuator/metrics/" + metric).queryParam("tag", "outcome:accepted"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value(metric))
                    .andExpect(jsonPath("$.measurements[0].statistic").value("COUNT"))
                    .andExpect(jsonPath("$.measurements[0].value").value(1.0))
                    .andExpect(content().string(not(containsString(correlationId.toString()))));
        }
        mvc.perform(get("/actuator/metrics/secondbrain.document.upload.duration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUnit").value("seconds"));
        mvc.perform(
                        get("/actuator/metrics/secondbrain.document.cleanup.batches")
                                .queryParam("tag", "outcome:completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));
        mvc.perform(
                        get("/actuator/metrics/secondbrain.document.cleanup.duration")
                                .queryParam("tag", "outcome:completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUnit").value("seconds"));
        mvc.perform(get("/actuator/metrics/secondbrain.document.cleanup.deleted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));
        assertThat(
                        meters.get("secondbrain.document.upload.duration")
                                .tag("outcome", "accepted")
                                .timer()
                                .getId()
                                .getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("outcome", "error");
    }

    // Explicit source only; it must not be component-scanned into other application tests.
    @EnableAutoConfiguration(
            exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import(DocumentTelemetry.class)
    static class TelemetryApplication {}
}
