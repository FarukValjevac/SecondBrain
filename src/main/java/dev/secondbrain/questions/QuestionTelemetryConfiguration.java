package dev.secondbrain.questions;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared by provider-only wiring and orchestration, including deterministic provider doubles. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "secondbrain.questions.enabled", havingValue = "true")
class QuestionTelemetryConfiguration {
    @Bean
    QuestionTelemetry questionTelemetry(
            ObjectProvider<MeterRegistry> meters,
            ObjectProvider<ObservationRegistry> observations) {
        var registry = meters.getIfAvailable();
        return registry == null
                ? QuestionTelemetry.disabled()
                : new QuestionTelemetry(
                        registry, observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }
}
