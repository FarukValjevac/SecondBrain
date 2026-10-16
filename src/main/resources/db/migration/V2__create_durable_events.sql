-- Compact keys survive payload cleanup so old publications cannot recreate completed work.
CREATE TABLE event_keys (
    event_id uuid PRIMARY KEY,
    document_id uuid NOT NULL,
    processing_version bigint NOT NULL CHECK (processing_version > 0),
    event_type varchar(64) NOT NULL,
    schema_version integer NOT NULL CHECK (schema_version > 0),
    fingerprint varchar(64) NOT NULL,
    UNIQUE (document_id, processing_version, event_type)
);

CREATE TABLE domain_events (
    event_id uuid PRIMARY KEY REFERENCES event_keys(event_id),
    occurred_at timestamptz NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    trace_parent varchar(55),
    reference_ids jsonb NOT NULL CHECK (jsonb_typeof(reference_ids) = 'object')
        CHECK (octet_length(reference_ids::text) <= 4096),
    created_at timestamptz NOT NULL
);

CREATE TABLE event_deliveries (
    delivery_id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES domain_events(event_id) ON DELETE CASCADE,
    handler_id varchar(64) NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'RUNNING', 'COMPLETED', 'SKIPPED', 'FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    eligible_at timestamptz NOT NULL,
    lease_owner uuid,
    lease_token uuid,
    lease_until timestamptz,
    deadline_at timestamptz,
    failure_code varchar(32),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (event_id, handler_id),
    CHECK ((state = 'RUNNING') =
        (lease_owner IS NOT NULL AND lease_token IS NOT NULL
         AND lease_until IS NOT NULL AND deadline_at IS NOT NULL)),
    CHECK (state = 'RUNNING' OR
        (lease_owner IS NULL AND lease_token IS NULL
         AND lease_until IS NULL AND deadline_at IS NULL))
);

CREATE INDEX event_deliveries_pending ON event_deliveries(eligible_at, delivery_id)
    WHERE state = 'PENDING';
CREATE INDEX event_deliveries_expired ON event_deliveries(lease_until, delivery_id)
    WHERE state = 'RUNNING';

CREATE TABLE event_delivery_attempts (
    delivery_id uuid NOT NULL REFERENCES event_deliveries(delivery_id) ON DELETE CASCADE,
    attempt integer NOT NULL CHECK (attempt > 0),
    lease_token uuid NOT NULL UNIQUE,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    outcome varchar(16) NOT NULL
        CHECK (outcome IN ('RUNNING', 'COMPLETED', 'SKIPPED', 'FAILED', 'EXPIRED')),
    failure_code varchar(32),
    PRIMARY KEY (delivery_id, attempt),
    CHECK ((outcome = 'RUNNING') = (finished_at IS NULL))
);
