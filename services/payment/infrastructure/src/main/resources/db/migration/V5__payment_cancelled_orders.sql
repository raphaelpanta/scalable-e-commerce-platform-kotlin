-- The orders the payment context knows to be cancelled (data-model sections 3.4 and 3.5), from their OrderCancelled
-- event: a charge that resolves approved for one of them (a charge racing the cancellation) is refunded at once, and
-- its RefundRecorded carries the owner's contact snapshot kept here (personal data, the same snapshot the event
-- carried; never logged). A pending charge of a cancelled order is stored voided.
CREATE TABLE cancelled_orders (
    order_id           uuid        PRIMARY KEY,
    account_id         uuid        NOT NULL,
    email              text        NOT NULL,
    phone              text        NULL,
    preferred_channels text[]      NOT NULL,
    recorded_at        timestamptz NOT NULL
);
