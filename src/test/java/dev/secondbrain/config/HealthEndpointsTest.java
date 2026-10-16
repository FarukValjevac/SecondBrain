package dev.secondbrain.config;

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

    @Autowired
    HealthEndpointsTest(MockMvc mvc, TestDatabaseHealth database) {
        this.mvc = mvc;
        this.database = database;
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
        for (String endpoint :
                new String[] {"", "/env", "/configprops", "/heapdump", "/metrics", "/loggers"}) {
            mvc.perform(MockMvcRequestBuilders.get("/actuator" + endpoint))
                    .andExpect(MockMvcResultMatchers.status().isNotFound());
        }
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
