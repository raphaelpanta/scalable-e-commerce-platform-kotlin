-- Stock reservations (data-model section 3.2, ADR 0002): one per order, all or nothing across the lines,
-- reserved -> committed | released. The unique order_id makes a concurrent second reservation of an order lose.

CREATE TABLE reservations (
    id          uuid PRIMARY KEY,
    order_id    uuid        NOT NULL UNIQUE,
    state       varchar(16) NOT NULL CHECK (state IN ('reserved', 'committed', 'released')),
    restocked   boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    resolved_at timestamptz,
    version     bigint      NOT NULL DEFAULT 0,
    CHECK (expires_at > created_at)
);

-- The expiry job looks for open reservations past their expiry.
CREATE INDEX reservations_open_expiry ON reservations (expires_at) WHERE state = 'reserved';

CREATE TABLE reservation_lines (
    reservation_id uuid    NOT NULL REFERENCES reservations (id) ON DELETE CASCADE,
    product_id     uuid    NOT NULL REFERENCES products (id),
    quantity       integer NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    position       integer NOT NULL,
    PRIMARY KEY (reservation_id, product_id)
);
