package dev.secondbrain.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.embeddings.CloudflareEmbeddingSettings;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;

@ExtendWith(OutputCaptureExtension.class)
class EmbeddingConfigurationTest {
    private static final String ACCOUNT = "a".repeat(32);
    private static final String TOKEN = "private-test-token-never-send";
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(EmbeddingConfiguration.class);

    @Test
    void ordinaryStartupNeedsNoCredentialsAndCreatesNoProvider() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CloudflareEmbeddingSettings.class);
                    assertThat(context).doesNotHaveBean(EmbeddingProvider.class);
                });
    }

    @Test
    void applicationPropertiesResolveTheExternalEnvFileNames() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.config.import=",
                        "SECONDBRAIN_EMBEDDINGS_ENABLED=true",
                        "CLOUDFLARE_ACCOUNT_ID=" + ACCOUNT,
                        "CLOUDFLARE_API_TOKEN=" + TOKEN,
                        "CLOUDFLARE_EMBEDDING_MODEL=" + EmbeddingModel.BGE_M3_ID,
                        "CLOUDFLARE_EMBEDDING_DIMENSIONS=1024")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(CloudflareEmbeddingSettings.class);
                            assertThat(context.getBean(CloudflareEmbeddingSettings.class).model())
                                    .isEqualTo(new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024));
                        });
    }

    @Test
    void explicitOptInValidatesSettingsWithoutCreatingANetworkClient(CapturedOutput output) {
        configured()
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var settings = context.getBean(CloudflareEmbeddingSettings.class);
                            assertThat(settings.model())
                                    .isEqualTo(new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024));
                            assertThat(settings.endpoint().toString())
                                    .isEqualTo(
                                            "https://api.cloudflare.com/client/v4/accounts/"
                                                    + ACCOUNT
                                                    + "/ai/v1/embeddings");
                            assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                            assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
                            assertThat(settings.batchSize()).isEqualTo(8);
                            assertThat(settings.apiToken()).isEqualTo(TOKEN);
                            assertThat(settings.toString()).doesNotContain(ACCOUNT, TOKEN);
                            assertThat(context).doesNotHaveBean(EmbeddingProvider.class);
                        });
        assertThat(output).doesNotContain(ACCOUNT, TOKEN);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CLOUDFLARE_ACCOUNT_ID=",
                "CLOUDFLARE_ACCOUNT_ID=https://other.invalid",
                "CLOUDFLARE_API_TOKEN=",
                "CLOUDFLARE_API_TOKEN=private token sentinel",
                "secondbrain.embeddings.model=",
                "secondbrain.embeddings.model=@cf/other/model",
                "secondbrain.embeddings.dimensions=0",
                "secondbrain.embeddings.dimensions=768",
                "secondbrain.embeddings.dimensions=invalid",
                "secondbrain.embeddings.connect-timeout=0s",
                "secondbrain.embeddings.connect-timeout=11s",
                "secondbrain.embeddings.request-timeout=1s",
                "secondbrain.embeddings.request-timeout=61s",
                "secondbrain.embeddings.request-timeout=invalid",
                "secondbrain.embeddings.batch-size=0",
                "secondbrain.embeddings.batch-size=33"
            })
    void rejectsMissingIncompatibleAndUnboundedConfigurationWithoutLeakingSecrets(
            String setting, CapturedOutput output) {
        configured()
                .withPropertyValues(setting)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
                            assertThat(context.getStartupFailure())
                                    .rootCause()
                                    .hasMessageNotContaining(TOKEN);
                            var diagnostic = new StringWriter();
                            context.getStartupFailure()
                                    .printStackTrace(new PrintWriter(diagnostic));
                            assertThat(diagnostic.toString())
                                    .doesNotContain(TOKEN, ACCOUNT, "private token sentinel");
                        });
        assertThat(output).doesNotContain(TOKEN, "private token sentinel");
    }

    @Test
    void missingModelAndCredentialsFailSafely(CapturedOutput output) {
        runner.withPropertyValues("secondbrain.embeddings.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseMessage(
                                            "Unsupported embedding model or dimensions");
                        });
        assertThat(output).doesNotContain(TOKEN);
    }

    private ApplicationContextRunner configured() {
        return runner.withPropertyValues(
                "secondbrain.embeddings.enabled=true",
                "CLOUDFLARE_ACCOUNT_ID=" + ACCOUNT,
                "CLOUDFLARE_API_TOKEN=" + TOKEN,
                "secondbrain.embeddings.model=" + EmbeddingModel.BGE_M3_ID,
                "secondbrain.embeddings.dimensions=1024");
    }
}
