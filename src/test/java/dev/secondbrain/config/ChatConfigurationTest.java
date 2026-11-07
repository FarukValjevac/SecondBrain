package dev.secondbrain.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.secondbrain.questions.ChatProvider;
import dev.secondbrain.questions.CloudflareChatProvider;
import dev.secondbrain.questions.CloudflareChatSettings;

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
class ChatConfigurationTest {
    private static final String ACCOUNT = "b".repeat(32);
    private static final String TOKEN = "synthetic-private-chat-token";
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ChatConfiguration.class);

    @Test
    void defaultAndDisabledConfigurationNeedNoModelCredentialsOrProvider() {
        runner.run(
                context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .doesNotHaveBean(CloudflareChatSettings.class)
                            .doesNotHaveBean(ChatProvider.class);
                });
        runner.withPropertyValues(
                        "secondbrain.questions.enabled=false",
                        "CLOUDFLARE_API_TOKEN=invalid secret sentinel")
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .doesNotHaveBean(CloudflareChatSettings.class);
                        });
    }

    @Test
    void explicitConfigurationValidatesSharedCredentialsAndLimitsWithoutMakingARequest(
            CapturedOutput output) {
        configured()
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(CloudflareChatSettings.class)
                                    .hasSingleBean(ChatProvider.class);
                            assertThat(context.getBean(ChatProvider.class))
                                    .isInstanceOf(CloudflareChatProvider.class);
                            var settings = context.getBean(CloudflareChatSettings.class);
                            assertThat(settings.endpoint().toString())
                                    .isEqualTo(
                                            "https://api.cloudflare.com/client/v4/accounts/"
                                                    + ACCOUNT
                                                    + "/ai/v1/chat/completions");
                            assertThat(settings.model())
                                    .isEqualTo(CloudflareChatSettings.LLAMA_3_3_70B_ID);
                            assertThat(settings.apiToken()).isEqualTo(TOKEN);
                            assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                            assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
                            assertThat(settings.maximumOutputTokens()).isEqualTo(512);
                            assertThat(settings.toString()).doesNotContain(ACCOUNT, TOKEN);
                        });
        assertThat(output).doesNotContain(ACCOUNT, TOKEN);
    }

    @Test
    void applicationConfigurationResolvesExternalNamesWithoutImportingThePrivateEnvFile() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.config.import=",
                        "SECONDBRAIN_QUESTIONS_ENABLED=true",
                        "CLOUDFLARE_ACCOUNT_ID=" + ACCOUNT,
                        "CLOUDFLARE_API_TOKEN=" + TOKEN,
                        "CLOUDFLARE_CHAT_MODEL=" + CloudflareChatSettings.LLAMA_3_3_70B_ID,
                        "CLOUDFLARE_CHAT_MAX_OUTPUT_TOKENS=256")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            context.getBean(CloudflareChatSettings.class)
                                                    .maximumOutputTokens())
                                    .isEqualTo(256);
                            assertThat(context).hasSingleBean(ChatProvider.class);
                        });
    }

    @Test
    void fakeProviderOverridesLiveConfigurationWithoutCredentialsOrStartupCalls() throws Exception {
        var fake = mock(ChatProvider.class);
        runner.withBean(ChatProvider.class, () -> fake)
                .withPropertyValues("secondbrain.questions.enabled=true")
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(ChatProvider.class)
                                    .doesNotHaveBean(CloudflareChatSettings.class);
                        });
        verify(fake, never()).generate(any());
    }

    @Test
    void disablingEmbeddingsOrEventsDoesNotChangeQuestionSettings() {
        configured()
                .withPropertyValues(
                        "secondbrain.embeddings.enabled=false", "secondbrain.events.enabled=false")
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(CloudflareChatSettings.class);
                        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CLOUDFLARE_ACCOUNT_ID=",
                "CLOUDFLARE_ACCOUNT_ID=https://other.invalid",
                "CLOUDFLARE_API_TOKEN=",
                "CLOUDFLARE_API_TOKEN=private token sentinel",
                "secondbrain.questions.model=",
                "secondbrain.questions.model=@cf/other/model",
                "secondbrain.questions.connect-timeout=0s",
                "secondbrain.questions.connect-timeout=11s",
                "secondbrain.questions.request-timeout=1s",
                "secondbrain.questions.request-timeout=61s",
                "secondbrain.questions.request-timeout=private-timeout-sentinel",
                "secondbrain.questions.max-output-tokens=0",
                "secondbrain.questions.max-output-tokens=2049",
                "secondbrain.questions.max-output-tokens=private-number-sentinel"
            })
    void rejectsMissingInvalidOrUnboundedValuesWithoutLeakingThem(
            String property, CapturedOutput output) {
        configured()
                .withPropertyValues(property)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
                            var diagnostics = new StringWriter();
                            context.getStartupFailure()
                                    .printStackTrace(new PrintWriter(diagnostics));
                            assertThat(diagnostics.toString())
                                    .doesNotContain(
                                            TOKEN,
                                            ACCOUNT,
                                            "private token sentinel",
                                            "private-timeout-sentinel",
                                            "private-number-sentinel",
                                            "https://other.invalid");
                        });
        assertThat(output)
                .doesNotContain(
                        TOKEN,
                        "private token sentinel",
                        "private-timeout-sentinel",
                        "private-number-sentinel");
    }

    private ApplicationContextRunner configured() {
        return runner.withPropertyValues(
                "secondbrain.questions.enabled=true",
                "CLOUDFLARE_ACCOUNT_ID=" + ACCOUNT,
                "CLOUDFLARE_API_TOKEN=" + TOKEN,
                "secondbrain.questions.model=" + CloudflareChatSettings.LLAMA_3_3_70B_ID);
    }
}
