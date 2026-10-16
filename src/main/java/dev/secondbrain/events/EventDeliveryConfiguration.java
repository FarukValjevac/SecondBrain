package dev.secondbrain.events;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "secondbrain.events.enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(EventDeliveryProperties.class)
class EventDeliveryConfiguration {
    @Bean
    EventMetrics eventMetrics(MeterRegistry registry) {
        return new EventMetrics(registry);
    }

    @Bean
    EventTracing eventTracing(ObjectProvider<Tracer> tracer) {
        return new EventTracing(tracer.getIfAvailable(() -> Tracer.NOOP));
    }

    @Bean
    EventBacklogMetrics eventBacklogMetrics(
            JdbcEventStore store,
            MeterRegistry registry,
            Clock clock,
            ObjectProvider<DurableEventWorker> worker) {
        return new EventBacklogMetrics(store, registry, clock, worker);
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock eventClock() {
        return Clock.systemUTC();
    }

    @Bean
    EventRegistry eventRegistry(
            ObjectProvider<EventHandler> handlers,
            ObjectProvider<EventEnvelope.Type> terminalTypes) {
        return new EventRegistry(
                handlers.orderedStream().toList(), terminalTypes.orderedStream().toList());
    }

    @Bean
    JdbcEventStore jdbcEventStore(
            JdbcClient jdbc,
            Clock clock,
            EventDeliveryProperties settings,
            PlatformTransactionManager transactions,
            EventMetrics metrics) {
        var retry =
                new EventRetryPolicy(
                        settings, clock, () -> ThreadLocalRandom.current().nextDouble());
        return new JdbcEventStore(
                jdbc, JsonMapper.builder().build(), clock, settings, retry, transactions, metrics);
    }

    @Bean
    EventPublisher eventPublisher(
            JdbcEventStore store, ObjectProvider<EventRegistry> registry, EventTracing tracing) {
        // Resolve after handler construction: handlers may themselves publish downstream events.
        return new EventPublisher(store, registry::getObject, tracing);
    }

    @Bean
    EventDispatcher eventDispatcher(
            JdbcEventStore store, EventRegistry registry, EventTracing tracing) {
        return new EventDispatcher(store, registry, tracing);
    }

    @Bean
    @ConditionalOnProperty(
            name = "secondbrain.events.polling-enabled",
            havingValue = "true",
            matchIfMissing = true)
    DurableEventWorker durableEventWorker(
            JdbcEventStore store,
            EventDispatcher dispatcher,
            EventDeliveryProperties settings,
            Clock clock) {
        return new DurableEventWorker(store, dispatcher, settings, clock);
    }
}
