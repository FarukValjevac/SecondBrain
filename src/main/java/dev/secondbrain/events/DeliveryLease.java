package dev.secondbrain.events;

import java.time.Instant;
import java.util.UUID;

record DeliveryLease(
        UUID deliveryId,
        String handlerId,
        UUID owner,
        UUID token,
        int attempt,
        Instant deadline,
        EventEnvelope event) {}
