package dev.secondbrain;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.config.import=",
            "secondbrain.embeddings.enabled=false",
            "secondbrain.events.enabled=false",
            // This smoke test deliberately has no database to include in readiness.
            "management.endpoint.health.group.readiness.include=readinessState"
        })
@ImportAutoConfiguration(
        exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class SecondBrainApplicationTest {

    private final ConfigurableApplicationContext applicationContext;

    @Autowired
    SecondBrainApplicationTest(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Test
    void startsWithDatabaseAutoConfigurationExcluded() {
        Assertions.assertThat(applicationContext.isActive()).isTrue();
        Assertions.assertThat(
                        applicationContext.getBeansOfType(
                                dev.secondbrain.embeddings.EmbeddingProvider.class))
                .isEmpty();
        Assertions.assertThat(
                        applicationContext.getBeansOfType(
                                dev.secondbrain.search.SearchService.class))
                .isEmpty();
    }

    @Test
    void writesConsoleLogsAsJsonWithSeparateStructuredFields(CapturedOutput output) {
        LoggerFactory.getLogger(SecondBrainApplicationTest.class)
                .atInfo()
                .addKeyValue("operation", "console_format_check")
                .addKeyValue("outcome", "success")
                .log("Structured console logging is configured");

        var lines =
                output.getOut()
                        .lines()
                        .filter(line -> line.contains("console_format_check"))
                        .toList();
        Assertions.assertThat(lines).hasSize(1);
        var entry = JsonMapper.builder().build().readTree(lines.getFirst());
        Assertions.assertThat(entry.get("@timestamp").asString()).isNotBlank();
        Assertions.assertThat(entry.get("level").asString()).isEqualTo("INFO");
        Assertions.assertThat(entry.get("logger_name").asString())
                .isEqualTo(SecondBrainApplicationTest.class.getName());
        Assertions.assertThat(entry.get("thread_name").asString()).isNotBlank();
        Assertions.assertThat(entry.get("message").asString())
                .isEqualTo("Structured console logging is configured");
        Assertions.assertThat(entry.get("operation").asString()).isEqualTo("console_format_check");
        Assertions.assertThat(entry.get("outcome").asString()).isEqualTo("success");
    }
}
