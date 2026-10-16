package dev.secondbrain.events;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

class EventDeliveryConfigurationTest {
    @Test
    void handlersCanDependOnThePublisherWithoutACircularDependency() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        EventDeliveryConfiguration.class, PublishingHandlerConfiguration.class)
                .withBean(JdbcClient.class, () -> Mockito.mock(JdbcClient.class))
                .withBean(
                        PlatformTransactionManager.class,
                        () -> Mockito.mock(PlatformTransactionManager.class))
                .withPropertyValues(
                        "secondbrain.events.enabled=true",
                        "secondbrain.events.polling-enabled=false")
                .run(
                        context -> {
                            Assertions.assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(EventPublisher.class)
                                    .hasSingleBean(EventRegistry.class)
                                    .doesNotHaveBean(DurableEventWorker.class);
                            Assertions.assertThat(
                                            context.getBean(EventRegistry.class)
                                                    .recipients(new EventEnvelope.Type("Probe", 1)))
                                    .containsExactly("probe");
                        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PublishingHandlerConfiguration {
        @Bean
        EventHandler publishingHandler(EventPublisher publisher) {
            return new EventHandler() {
                @Override
                public String id() {
                    return "probe";
                }

                @Override
                public EventEnvelope.Type eventType() {
                    return new EventEnvelope.Type("Probe", 1);
                }

                @Override
                public Effect prepare(EventEnvelope event) {
                    return () -> {
                        UUID canonical = publisher.publish(event);
                        Assertions.assertThat(canonical).isNotNull();
                        return Outcome.COMPLETED;
                    };
                }
            };
        }
    }
}
