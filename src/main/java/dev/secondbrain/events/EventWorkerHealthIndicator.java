package dev.secondbrain.events;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("eventWorkerHealthIndicator")
final class EventWorkerHealthIndicator implements HealthIndicator {
    private final ObjectProvider<DurableEventWorker> worker;
    private final boolean required;

    EventWorkerHealthIndicator(
            ObjectProvider<DurableEventWorker> worker,
            @Value("${secondbrain.events.enabled:true}") boolean enabled,
            @Value("${secondbrain.events.polling-enabled:true}") boolean polling) {
        this.worker = worker;
        this.required = enabled && polling;
    }

    @Override
    public Health health() {
        var current = worker.getIfAvailable();
        return (!required || (current != null && current.isOperational()))
                ? Health.up().build()
                : Health.down().build();
    }
}
