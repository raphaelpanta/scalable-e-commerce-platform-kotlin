-- Removes the dataset of seed-10k.sql (T115) and everything the performance run attached to it in the catalogue
-- database: stock reservations and stock adjustments of the PERF products. Business data is never deleted in
-- production (data-model section 1); this is a test-only clean-up of a throw-away local stack.
-- Orders and carts live in the order and cart databases and keep their (frozen) copies: for a pristine stack use
-- `docker compose down -v` instead.

BEGIN;

DELETE FROM reservation_lines
 WHERE product_id IN (SELECT id FROM products WHERE sku LIKE 'PERF-%');

-- Reservations whose every line was a PERF line are empty now (a reservation always has at least one line).
DELETE FROM reservations r
 WHERE NOT EXISTS (SELECT 1 FROM reservation_lines l WHERE l.reservation_id = r.id);

DELETE FROM stock_adjustments
 WHERE product_id IN (SELECT id FROM products WHERE sku LIKE 'PERF-%');

-- product_images and inventory_levels go with the product (ON DELETE CASCADE).
DELETE FROM products WHERE sku LIKE 'PERF-%';

COMMIT;

ANALYZE products;

SELECT count(*) AS remaining_perf_products FROM products WHERE sku LIKE 'PERF-%';
