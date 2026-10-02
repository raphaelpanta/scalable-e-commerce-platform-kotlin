-- Performance dataset (T115, SC-002/SC-003): 10,000 products, each with one stock level and one primary image, spread
-- over the three seed categories of services/catalog/.../db/seed/R__seed.sql (which must be loaded: SEED=true).
-- Not a Flyway script on purpose (it is outside the service's migration locations): apply it with seed-10k-apply.sh.
--
-- Deterministic and idempotent: ids and SKUs are derived from the series number n, every INSERT is
-- ON CONFLICT DO NOTHING, so a second run changes nothing (stock already consumed by a checkout run is NOT reset: use
-- seed-10k-remove.sql first for that).
--   product id   9e4f0000-0000-4000-8000-<n, 12 digits>     (k6 script rebuilds these ids: keep both in sync)
--   image id     9e4f0000-0000-4000-9000-<n, 12 digits>
--   SKU          PERF-<n, 6 digits>                         PERF-000001 .. PERF-010000
--   name         "<adjective> <noun> <n>"                   searchable by the adjective and the noun lists below
--   price_minor  990 .. 99990                               990 + (n * 7919) mod 99001
--   stock        5 .. 500                                   5 + (n * 37) mod 496
--   category     round robin over the three seed categories

BEGIN;

-- The seed categories must exist, otherwise the foreign key would fail half way with a confusing message.
DO $$
BEGIN
    IF (SELECT count(*) FROM categories
         WHERE id IN ('5eed0000-0000-4000-8000-0000000000c1',
                      '5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45',
                      '5eed0000-0000-4000-8000-0000000000c3')) <> 3 THEN
        RAISE EXCEPTION 'seed categories are missing: start the stack with SEED=true before applying seed-10k.sql';
    END IF;
END
$$;

WITH series AS (
    SELECT n,
           (ARRAY['Compact', 'Premium', 'Classic', 'Rugged', 'Lightweight', 'Ergonomic', 'Portable', 'Vintage',
                  'Modular', 'Insulated'])[1 + n % 10] AS adjective,
           (ARRAY['Kettle', 'Backpack', 'Lantern', 'Sneaker', 'Grinder', 'Thermos', 'Jacket', 'Tent', 'Mug',
                  'Stove', 'Poles', 'Bottle'])[1 + (n / 10) % 12] AS noun,
           (ARRAY['5eed0000-0000-4000-8000-0000000000c1',
                  '5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45',
                  '5eed0000-0000-4000-8000-0000000000c3'])[1 + n % 3]::uuid AS category_id
      FROM generate_series(1, 10000) AS n
)
INSERT INTO products (id, sku, name, description, price_minor, currency, category_id, sale_state, created_at,
                      updated_at, version)
SELECT ('9e4f0000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       'PERF-' || lpad(n::text, 6, '0'),
       adjective || ' ' || noun || ' ' || n,
       'Synthetic load-test item ' || lpad(n::text, 6, '0') || ' (' || lower(adjective) || ' ' || lower(noun) || ').',
       990 + ((n::bigint * 7919) % 99001),
       'BRL',
       category_id,
       'active',
       timestamptz '2026-10-02T08:00:00Z' + (n * interval '1 second'),
       timestamptz '2026-10-02T08:00:00Z' + (n * interval '1 second'),
       0
  FROM series
ON CONFLICT DO NOTHING;

INSERT INTO product_images (id, product_id, url, alt_text, is_primary, position)
SELECT ('9e4f0000-0000-4000-9000-' || lpad(n::text, 12, '0'))::uuid,
       ('9e4f0000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       'https://cdn.example.test/products/perf-' || lpad(n::text, 6, '0') || '.jpg',
       'Load-test product ' || n,
       true,
       0
  FROM generate_series(1, 10000) AS n
ON CONFLICT DO NOTHING;

INSERT INTO inventory_levels (product_id, on_hand, reserved, version, updated_at)
SELECT ('9e4f0000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       5 + ((n * 37) % 496),
       0,
       0,
       timestamptz '2026-10-02T08:00:00Z'
  FROM generate_series(1, 10000) AS n
ON CONFLICT DO NOTHING;

COMMIT;

-- Fresh planner statistics: the browse queries must not be measured against the estimates of an empty table.
ANALYZE products;
ANALYZE product_images;
ANALYZE inventory_levels;

SELECT count(*) AS perf_products,
       min(price_minor) AS min_price_minor,
       max(price_minor) AS max_price_minor
  FROM products
 WHERE sku LIKE 'PERF-%';
