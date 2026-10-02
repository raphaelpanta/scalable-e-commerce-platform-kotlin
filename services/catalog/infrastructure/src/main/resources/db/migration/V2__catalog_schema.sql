-- Catalogue (data-model section 3.2): categories, products with their images, and one stock level per product.
-- Business records are never deleted (data-model section 1): products are withdrawn instead.

CREATE TABLE categories (
    id          uuid PRIMARY KEY,
    name        varchar(120)  NOT NULL,
    description varchar(1000),
    parent_id   uuid REFERENCES categories (id),
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL,
    version     bigint        NOT NULL DEFAULT 0
);

-- A name is unique among the children of one parent (roots share the nil parent), compared case-insensitively.
CREATE UNIQUE INDEX categories_name_per_parent
    ON categories (coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));
CREATE INDEX categories_parent ON categories (parent_id);

CREATE TABLE products (
    id          uuid PRIMARY KEY,
    sku         varchar(32)   NOT NULL UNIQUE,
    name        varchar(120)  NOT NULL,
    description varchar(4000),
    price_minor bigint        NOT NULL CHECK (price_minor > 0),
    currency    char(3)       NOT NULL,
    category_id uuid          NOT NULL REFERENCES categories (id),
    sale_state  varchar(16)   NOT NULL CHECK (sale_state IN ('active', 'withdrawn')),
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL,
    version     bigint        NOT NULL DEFAULT 0
);

CREATE INDEX products_category ON products (category_id);
CREATE INDEX products_name ON products (lower(name));

CREATE TABLE product_images (
    id         uuid PRIMARY KEY,
    product_id uuid          NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    url        varchar(2048) NOT NULL,
    alt_text   varchar(200),
    is_primary boolean       NOT NULL,
    position   integer       NOT NULL
);

CREATE INDEX product_images_product ON product_images (product_id, position);
-- Exactly one primary image per product that has images (the domain keeps the "at least one").
CREATE UNIQUE INDEX product_images_one_primary ON product_images (product_id) WHERE is_primary;

-- available = on_hand - reserved, never negative: every change is a guarded UPDATE and these checks are the last
-- line of defence (FR-012, SC-004).
CREATE TABLE inventory_levels (
    product_id uuid PRIMARY KEY REFERENCES products (id) ON DELETE CASCADE,
    on_hand    integer     NOT NULL CHECK (on_hand >= 0),
    reserved   integer     NOT NULL CHECK (reserved >= 0),
    version    bigint      NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (reserved <= on_hand)
);
