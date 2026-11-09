-- attempts remains the lifetime sequence used by the immutable attempt history.
ALTER TABLE event_deliveries
    ADD COLUMN cycle_attempts integer NOT NULL DEFAULT 0 CHECK (cycle_attempts >= 0),
    ADD COLUMN retry_cycle integer NOT NULL DEFAULT 0 CHECK (retry_cycle >= 0);
UPDATE event_deliveries SET cycle_attempts = attempts;
ALTER TABLE event_deliveries ADD CONSTRAINT delivery_cycle_within_history
    CHECK (cycle_attempts <= attempts);

-- Operator intent/audit, not another queue. Existing delivery identity is never replaced.
CREATE TABLE event_delivery_retries (
    delivery_id uuid NOT NULL REFERENCES event_deliveries(delivery_id) ON DELETE CASCADE,
    retry_cycle integer NOT NULL CHECK (retry_cycle > 0),
    requested_at timestamptz NOT NULL,
    correlation_id uuid NOT NULL,
    PRIMARY KEY (delivery_id, retry_cycle)
);
