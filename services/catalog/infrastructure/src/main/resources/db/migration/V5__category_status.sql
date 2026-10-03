-- Category withdrawal (FR-002, data-model section 3.2): a category is active or withdrawn, never deleted. A withdrawn
-- category hides itself, the categories beneath it and their products from shoppers, and accepts no new products.

ALTER TABLE categories
    ADD COLUMN status varchar(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'withdrawn'));

-- Every shopper read looks the withdrawn categories up first; there are few of them.
CREATE INDEX categories_withdrawn ON categories (id) WHERE status = 'withdrawn';
