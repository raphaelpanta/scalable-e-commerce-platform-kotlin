-- Messaging tables shipped by libs/platform-messaging (docs/service-conventions.md §4 and §5).
-- Every service lists classpath:db/messaging next to classpath:db/migration; this script runs after the
-- generator's V1__baseline.sql and before the service's own V2__ scripts.

-- Transactional outbox: written in the same transaction as the aggregate change, relayed to Kafka by
-- com.ecommerce.platform.messaging.outbox.OutboxRelay (at-least-once, no dual write).
CREATE TABLE outbox (
    id           uuid PRIMARY KEY,                     -- the envelope eventId
    -- Insertion order: rows written in one transaction share created_at, the identity keeps their order so
    -- that the events of one aggregate reach their partition in the order they were written.
    position     bigint GENERATED ALWAYS AS IDENTITY,
    aggregate_id text        NOT NULL,
    topic        text        NOT NULL,
    event_type   text        NOT NULL,
    event_key    text        NOT NULL,                 -- Kafka message key (the aggregate id)
    payload      jsonb       NOT NULL,                 -- the complete envelope, sent as the Kafka record value
    headers      jsonb       NOT NULL,                 -- Kafka headers: eventId, type, correlationId
    occurred_at  timestamptz NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz NULL,
    attempts     int         NOT NULL DEFAULT 0,
    last_error   text        NULL
);

CREATE INDEX outbox_unpublished_idx ON outbox (published_at, created_at);

-- Idempotent consumers: one row per (eventId, consumer) handled; repeats are skipped (FR-022).
-- Rows older than platform.messaging.processed-events.retention (7 days) are purged hourly.
CREATE TABLE processed_event (
    event_id     uuid        NOT NULL,
    consumer     text        NOT NULL,
    processed_at timestamptz NOT NULL,
    PRIMARY KEY (event_id, consumer)
);

CREATE INDEX processed_event_processed_at_idx ON processed_event (processed_at);
