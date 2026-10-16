package dev.secondbrain;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "secondbrain.events.enabled=false",
            // This smoke test deliberately has no database to include in readiness.
            "management.endpoint.health.group.readiness.include=readinessState"
        })
@ImportAutoConfiguration(
        exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
class SecondBrainApplicationTest {

    private final ConfigurableApplicationContext applicationContext;

    @Autowired
    SecondBrainApplicationTest(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Test
    void startsWithDatabaseAutoConfigurationExcluded() {
        Assertions.assertThat(applicationContext.isActive()).isTrue();
    }
}
