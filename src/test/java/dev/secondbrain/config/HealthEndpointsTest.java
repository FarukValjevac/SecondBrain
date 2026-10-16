package dev.secondbrain.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.util.concurrent.atomic.AtomicBoolean;

@SpringBootTest(properties = "secondbrain.events.enabled=false")
@AutoConfigureMockMvc
@ImportAutoConfiguration(
        exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@Import(HealthEndpointsTest.TestHealthConfiguration.class)
class HealthEndpointsTest {

    private final MockMvc mvc;
    private final TestDatabaseHealth database;
    private final MeterRegistry meters;

    @Autowired
    HealthEndpointsTest(MockMvc mvc, TestDatabaseHealth database, MeterRegistry meters) {
        this.mvc = mvc;
        this.database = database;
        this.meters = meters;
    }

    @AfterEach
    void restoreDatabaseHealth() {
        database.available.set(true);
    }

    @Test
    void exposesHealthyStatusWithoutPrivateDetails() throws Exception {
        expectHealth("/actuator/health", 200, "UP");
        expectHealth("/actuator/health/liveness", 200, "UP");
        expectHealth("/actuator/health/readiness", 200, "UP");
    }

    @Test
    void databaseFailureAffectsReadinessAndRecoversWithoutChangingLiveness() throws Exception {
        database.available.set(false);

        expectHealth("/actuator/health", 503, "DOWN");
        expectHealth("/actuator/health/readiness", 503, "DOWN");
        expectHealth("/actuator/health/liveness", 200, "UP");

        database.available.set(true);
        expectHealth("/actuator/health/readiness", 200, "UP");
    }

    @Test
    void doesNotExposeOtherManagementEndpoints() throws Exception {
        for (String endpoint : new String[] {"", "/env", "/configprops", "/heapdump", "/loggers"}) {
            mvc.perform(MockMvcRequestBuilders.get("/actuator" + endpoint))
                    .andExpect(MockMvcResultMatchers.status().isNotFound());
        }
    }

    @Test
    void exposesBuiltInMetricsWithTheirUnits() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/actuator/metrics"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(
                        MockMvcResultMatchers.jsonPath("$.names")
                                .value(Matchers.hasItems("process.uptime", "jvm.memory.used")));

        for (String[] metric :
                new String[][] {{"process.uptime", "seconds"}, {"jvm.memory.used", "bytes"}}) {
            mvc.perform(MockMvcRequestBuilders.get("/actuator/metrics/" + metric[0]))
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.name").value(metric[0]))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.baseUnit").value(metric[1]))
                    .andExpect(
                            MockMvcResultMatchers.jsonPath("$.measurements[0].value").isNumber());
        }
    }

    @Test
    void recordsLibraryRequestsWithoutPrivateQueryValuesInMetricTags() throws Exception {
        for (String query :
                new String[] {"sentinel-private-query-one", "sentinel-private-query-two"}) {
            mvc.perform(MockMvcRequestBuilders.get("/").queryParam("q", query))
                    .andExpect(MockMvcResultMatchers.status().isOk());
        }

        var requests =
                meters.find("http.server.requests").tags("method", "GET", "uri", "/").timers();
        Assertions.assertThat(requests).hasSize(1);
        Assertions.assertThat(requests.iterator().next().count()).isGreaterThanOrEqualTo(2);
        Assertions.assertThat(requests.iterator().next().getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder(
                        "method", "uri", "status", "outcome", "error", "exception");
        for (var meter : meters.getMeters()) {
            Assertions.assertThat(meter.getId().getTags().toString())
                    .doesNotContain("sentinel-private");
        }

        mvc.perform(
                        MockMvcRequestBuilders.get("/actuator/metrics/http.server.requests")
                                .queryParam("tag", "uri:/", "method:GET"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.baseUnit").value("seconds"))
                .andExpect(
                        MockMvcResultMatchers.content()
                                .string(Matchers.not(Matchers.containsString("sentinel-private"))));
    }

    private void expectHealth(String path, int status, String health) throws Exception {
        String groups =
                path.equals("/actuator/health") ? ",\"groups\":[\"liveness\",\"readiness\"]" : "";
        mvc.perform(MockMvcRequestBuilders.get(path))
                .andExpect(MockMvcResultMatchers.status().is(status))
                .andExpect(
                        MockMvcResultMatchers.content()
                                .json(
                                        "{\"status\":\"" + health + "\"" + groups + "}",
                                        JsonCompareMode.STRICT));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestHealthConfiguration {
        @Bean
        TestDatabaseHealth dbHealthIndicator() {
            return new TestDatabaseHealth();
        }
    }

    /** Simulates database availability; it does not verify a real PostgreSQL connection. */
    static final class TestDatabaseHealth implements HealthIndicator {
        private final AtomicBoolean available = new AtomicBoolean(true);

        @Override
        public Health health() {
            return (available.get() ? Health.up() : Health.down())
                    .withDetail("private", "sentinel-private-database-detail")
                    .build();
        }
    }
}
