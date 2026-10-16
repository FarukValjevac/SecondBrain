package dev.secondbrain.events;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** IDs only: document text and provider inputs must never become event payloads. */
public record EventEnvelope(
        UUID eventId,
        Type type,
        UUID documentId,
        long processingVersion,
        Instant occurredAt,
        UUID correlationId,
        UUID causationId,
        String traceParent,
        Map<String, UUID> references) {

    private static final Pattern REFERENCE_NAME = Pattern.compile("[a-z][A-Za-z0-9]{0,63}");
    private static final Pattern TRACE_PARENT =
            Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");

    /** causationId and traceParent may be null for a root event without a producer span. */
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        if (processingVersion < 1) {
            throw new IllegalArgumentException("processingVersion must be positive");
        }
        references = Map.copyOf(references);
        if (references.size() > 16
                || references.keySet().stream()
                        .anyMatch(key -> !REFERENCE_NAME.matcher(key).matches())) {
            throw new IllegalArgumentException("At most 16 named UUID references are allowed");
        }
        if (traceParent != null
                && (!TRACE_PARENT.matcher(traceParent).matches()
                        || traceParent.substring(3, 35).equals("0".repeat(32))
                        || traceParent.substring(36, 52).equals("0".repeat(16)))) {
            throw new IllegalArgumentException("Invalid W3C version-00 traceparent");
        }
    }

    public record Type(String name, int schemaVersion) {
        public Type {
            if (name == null || !name.matches("[A-Z][A-Za-z0-9]{0,63}") || schemaVersion < 1) {
                throw new IllegalArgumentException("Invalid event type or schema version");
            }
        }
    }
}
