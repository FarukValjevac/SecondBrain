package dev.secondbrain.events;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class EventRegistry {
    private final Map<String, EventHandler> handlers;
    private final Set<EventEnvelope.Type> types;

    EventRegistry(List<EventHandler> handlers, List<EventEnvelope.Type> terminalTypes) {
        var byId = new HashMap<String, EventHandler>();
        var knownTypes = new HashSet<>(terminalTypes);
        for (var handler : handlers) {
            if (handler.id() == null
                    || !handler.id().matches("[a-z][a-z0-9.-]{0,63}")
                    || byId.putIfAbsent(handler.id(), handler) != null) {
                throw new IllegalArgumentException("Invalid or duplicate event handler ID");
            }
            knownTypes.add(handler.eventType());
        }
        this.handlers = Map.copyOf(byId);
        this.types = Set.copyOf(knownTypes);
    }

    boolean supports(EventEnvelope.Type type) {
        return types.contains(type);
    }

    List<String> recipients(EventEnvelope.Type type) {
        if (!supports(type)) {
            throw new IllegalArgumentException("Unregistered event type/schema");
        }
        return handlers.values().stream()
                .filter(handler -> handler.eventType().equals(type))
                .map(EventHandler::id)
                .sorted()
                .toList();
    }

    Optional<EventHandler> handler(String id) {
        return Optional.ofNullable(handlers.get(id));
    }
}
