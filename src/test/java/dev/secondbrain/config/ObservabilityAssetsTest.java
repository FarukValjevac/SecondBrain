package dev.secondbrain.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

class ObservabilityAssetsTest {
    @Test
    void dashboardQueriesUseTheProvisionedSourceSafeScopeAndTimeUnits() throws Exception {
        var dashboard =
                JsonMapper.builder()
                        .build()
                        .readTree(Path.of("observability/secondbrain.json").toFile());
        assertThat(dashboard.path("uid").asString()).isEqualTo("secondbrain-processing");
        assertThat(dashboard.path("panels").size()).isGreaterThanOrEqualTo(12);
        for (var panel : dashboard.path("panels")) {
            assertThat(panel.path("datasource").path("uid").asString()).isEqualTo("prometheus");
            for (var target : panel.path("targets")) {
                String query = target.path("expr").asString();
                assertThat(query)
                        .contains("service_name=\"secondbrain\"")
                        .doesNotContain("document_id", "correlation_id", "$", "filename", "prompt");
                if (query.contains("_seconds"))
                    assertThat(panel.path("fieldConfig").path("defaults").path("unit").asString())
                            .isEqualTo("s");
            }
        }
        var provisioning = yaml("observability/dashboards.yaml");
        assertThat(provisioning.get("providers")).isInstanceOf(List.class);
        var provider = (Map<?, ?>) ((List<?>) provisioning.get("providers")).getFirst();
        assertThat(((Map<?, ?>) provider.get("options")).get("path"))
                .isEqualTo("/otel-lgtm/secondbrain.json");
    }

    @Test
    void composeAndRuntimeOptInsKeepPortsDataAndDefaultsSeparate() throws Exception {
        var compose = yaml("compose.yaml");
        var services = (Map<?, ?>) compose.get("services");
        var backend = (Map<?, ?>) services.get("observability");
        assertThat(backend.get("image").toString())
                .matches("grafana/otel-lgtm:[0-9]+\\.[0-9]+\\.[0-9]+");
        assertThat(backend.get("profiles")).isEqualTo(List.of("observability"));
        for (var port : (List<?>) backend.get("ports"))
            assertThat(port.toString()).startsWith("127.0.0.1:");
        assertThat(backend.containsKey("depends_on")).isFalse();
        for (var volume : (List<?>) backend.get("volumes")) {
            var source = volume.toString().split(":")[0];
            if (!source.equals("./data/observability")) assertThat(Path.of(source)).exists();
        }
        var defaults = properties("application.properties");
        assertThat(defaults.getProperty("management.otlp.metrics.export.enabled"))
                .isEqualTo("false");
        assertThat(defaults.getProperty("management.logging.export.otlp.enabled"))
                .isEqualTo("false");
        assertThat(defaults.getProperty("management.opentelemetry.tracing.export.max-queue-size"))
                .isEqualTo("256");
        var profile = properties("application-observability.properties");
        assertThat(profile.getProperty("management.otlp.metrics.export.enabled")).isEqualTo("true");
        assertThat(profile.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
        var tempo = yaml("observability/tempo.yaml");
        var overrides = (Map<?, ?>) ((Map<?, ?>) tempo.get("overrides")).get("defaults");
        assertThat(((Map<?, ?>) overrides.get("compaction")).get("block_retention"))
                .isEqualTo("24h");
    }

    private static Map<?, ?> yaml(String path) throws Exception {
        try (var input = Files.newInputStream(Path.of(path))) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        }
    }

    private static Properties properties(String name) throws Exception {
        var properties = new Properties();
        try (var input = ObservabilityAssetsTest.class.getResourceAsStream("/" + name)) {
            properties.load(input);
        }
        return properties;
    }
}
