-- Product search (FR-001, SC-002/SC-003): the free-text filter matches the term anywhere in the name or the
-- description, case-insensitively (R2dbcProductRepository MATCHES: `lower(p.name) LIKE '%term%'` OR
-- `lower(coalesce(p.description, '')) LIKE '%term%'`). A B-tree cannot serve a leading wildcard, so every search was
-- a sequential scan of `products`. Trigram GIN indexes on exactly those two expressions serve both arms (a BitmapOr
-- of the two index scans). pg_trgm ships with the standard postgres image and is a trusted extension.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX products_name_trgm ON products USING gin (lower(name) gin_trgm_ops);
CREATE INDEX products_description_trgm ON products USING gin (lower(coalesce(description, '')) gin_trgm_ops);
