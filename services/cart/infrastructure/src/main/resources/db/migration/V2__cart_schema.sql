-- Cart schema (data-model section 3.3). A cart belongs to exactly one owner: an account (one cart per account) or
-- the holder of an anonymous token, of which only the SHA-256 hash is stored. `version` is the optimistic-locking
-- token and feeds the derived cart revision (ADR 0003); `updated_at` drives the 30-day purge of idle anonymous carts.
CREATE TABLE cart (
    id         uuid PRIMARY KEY,
    account_id uuid        NULL UNIQUE,
    token_hash text        NULL UNIQUE,
    version    bigint      NOT NULL CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT cart_single_owner CHECK ((account_id IS NULL) <> (token_hash IS NULL))
);

CREATE INDEX cart_anonymous_idle_idx ON cart (updated_at) WHERE token_hash IS NOT NULL;

-- One line per product and cart, 1..99 units, with the snapshots taken when it was added; `position` keeps the
-- order in which the shopper added the products.
CREATE TABLE cart_line (
    id                 uuid PRIMARY KEY,
    cart_id            uuid        NOT NULL REFERENCES cart (id) ON DELETE CASCADE,
    product_id         uuid        NOT NULL,
    sku                text        NOT NULL,
    name               text        NOT NULL,
    quantity           int         NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    price_at_add_minor bigint      NOT NULL CHECK (price_at_add_minor >= 0),
    currency           char(3)     NOT NULL,
    added_at           timestamptz NOT NULL,
    position           int         NOT NULL,
    CONSTRAINT cart_line_one_per_product UNIQUE (cart_id, product_id)
);
