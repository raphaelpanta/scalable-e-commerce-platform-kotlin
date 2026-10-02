-- Attributed stock adjustments (FR-002, data-model section 3.2): append-only, kept indefinitely. actor_id is the
-- operator's account id (pseudonymous); no personal data is stored here.

CREATE TABLE stock_adjustments (
    id                 uuid PRIMARY KEY,
    product_id         uuid         NOT NULL REFERENCES products (id),
    delta              integer      NOT NULL CHECK (delta <> 0),
    reason             varchar(200) NOT NULL,
    actor_id           uuid         NOT NULL,
    adjusted_at        timestamptz  NOT NULL,
    previous_available integer      NOT NULL CHECK (previous_available >= 0),
    new_available      integer      NOT NULL CHECK (new_available >= 0)
);

CREATE INDEX stock_adjustments_product ON stock_adjustments (product_id, adjusted_at);
