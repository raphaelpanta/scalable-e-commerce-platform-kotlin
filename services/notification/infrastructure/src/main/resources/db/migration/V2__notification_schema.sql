-- Notification schema (data-model §3.6, T092). Personal data (recipient addresses, message bodies) is never
-- logged; it is cleared when the account is deleted (FR-007, data-model §5).

-- Recipient read model, built from AccountRegistered / AccountVerified, anonymised on AccountDeleted.
CREATE TABLE recipients (
    account_id     uuid        PRIMARY KEY,
    email_verified boolean     NOT NULL DEFAULT false,
    anonymised     boolean     NOT NULL DEFAULT false,
    updated_at     timestamptz NOT NULL
);

-- One message to one recipient on one channel; unique per (source event, kind, channel) = dedupe by eventId.
CREATE TABLE notifications (
    id                  uuid        PRIMARY KEY,
    source_event_id     uuid        NOT NULL,
    kind                text        NOT NULL CHECK (kind IN ('account_verification', 'password_reset',
                                        'order_confirmation', 'payment_failure', 'order_shipped', 'order_delivered',
                                        'order_cancelled', 'refund_confirmation')),
    channel             text        NOT NULL CHECK (channel IN ('email', 'sms')),
    account_id          uuid        NOT NULL,
    recipient_address   text        NULL,              -- email or E.164 phone; NULL once suppressed or anonymised
    subject             text        NOT NULL,
    body                text        NOT NULL,
    order_id            uuid        NULL,
    correlation_id      text        NOT NULL,
    status              text        NOT NULL CHECK (status IN ('queued', 'sent', 'failed', 'suppressed')),
    attempts            int         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at     timestamptz NULL,
    last_attempt_at     timestamptz NULL,
    last_error_category text        NULL,
    last_error          text        NULL,              -- client-safe reason, never an address or a body
    created_at          timestamptz NOT NULL,
    sent_at             timestamptz NULL,
    failed_at           timestamptz NULL,
    CONSTRAINT notifications_source_unique UNIQUE (source_event_id, kind, channel)
);

-- Due work of the delivery scheduler.
CREATE INDEX notifications_due_idx ON notifications (next_attempt_at) WHERE status = 'queued';
-- Shopper history, newest first.
CREATE INDEX notifications_account_idx ON notifications (account_id, created_at DESC);
-- Operator failed-delivery view, newest failure first.
CREATE INDEX notifications_failed_idx ON notifications (failed_at DESC) WHERE status = 'failed';

-- Append-only attempt history.
CREATE TABLE delivery_attempts (
    notification_id  uuid        NOT NULL REFERENCES notifications (id),
    attempt          int         NOT NULL CHECK (attempt >= 1),
    attempted_at     timestamptz NOT NULL,
    succeeded        boolean     NOT NULL,
    failure_category text        NULL,
    failure_reason   text        NULL
);

CREATE INDEX delivery_attempts_notification_idx ON delivery_attempts (notification_id, attempted_at);
