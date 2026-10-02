-- Seed catalogue (T028, Spring profile `seed`, SEED=true): 3 categories and 20 products with stock, among them the
-- fixed example products of contracts/internal/pact-interactions.md, one zero-stock product (Ceramic Mug) and one
-- withdrawn product (Vintage Kettle). Ids are deterministic so the acceptance suite and manual checks can rely on them
-- (services/catalog/README.md). Repeatable and idempotent: rows that exist already are left as they are.

INSERT INTO categories (id, name, description, parent_id, created_at, updated_at, version) VALUES
    ('5eed0000-0000-4000-8000-0000000000c1', 'Kitchen & Coffee', 'Espresso machines, beans, mugs and kettles.', NULL, '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45', 'Footwear', 'Shoes and boots', NULL, '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-0000000000c3', 'Outdoor', 'Tents, sleeping bags and trail gear.', NULL, '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0)
ON CONFLICT DO NOTHING;

INSERT INTO products (id, sku, name, description, price_minor, currency, category_id, sale_state, created_at,
                      updated_at, version) VALUES
    ('9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01', 'ESP-MACH-01', 'Espresso Machine', 'Pump espresso machine with steam wand.', 14900, 'BRL', '5eed0000-0000-4000-8000-0000000000c1', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02', 'CB-1KG', 'Coffee Beans 1kg', 'Medium roast arabica beans.', 2450, 'BRL', '5eed0000-0000-4000-8000-0000000000c1', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('b7e1c4d2-3f58-4a96-8d20-5c1e9a7b3d44', 'MUG-CER-01', 'Ceramic Mug', 'Stoneware mug, 350 ml.', 3290, 'BRL', '5eed0000-0000-4000-8000-0000000000c1', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d', 'KTL-VINT-01', 'Vintage Kettle', 'Enamel stovetop kettle.', 25900, 'BRL', '5eed0000-0000-4000-8000-0000000000c1', 'withdrawn', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-000000000105', 'KTL-POUR-01', 'Pour-Over Kettle', 'Gooseneck kettle for pour-over coffee.', 18900, 'BRL', '5eed0000-0000-4000-8000-0000000000c1', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-000000000106', 'GRD-BURR-01', 'Burr Coffee Grinder', 'Conical burr grinder with 40 settings.', 32900, 'BRL', '5eed0000-0000-4000-8000-0000000000c1', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-000000000107', 'FRT-MILK-01', 'Milk Frother', 'Handheld milk frother.', 8990, 'BRL', '5eed0000-0000-4000-8000-0000000000c1', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-000000000108', 'PRS-FR-1L', 'French Press 1L', 'Glass and steel French press.', 12900, 'BRL', '5eed0000-0000-4000-8000-0000000000c1', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21', 'TRS-001', 'Trail Running Shoes', 'Lightweight shoes with a grippy sole.', 9490, 'BRL', '5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-000000000110', 'RRS-001', 'Road Running Shoes', 'Cushioned shoes for long road runs.', 11990, 'BRL', '5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-000000000111', 'HKB-001', 'Hiking Boots', 'Waterproof leather hiking boots.', 24990, 'BRL', '5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-000000000112', 'SCK-WOOL-3', 'Wool Socks (3 pairs)', 'Merino wool hiking socks.', 4990, 'BRL', '5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-000000000113', 'SNK-CNV-01', 'Canvas Sneakers', 'Everyday canvas sneakers.', 15990, 'BRL', '5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-000000000114', 'SND-LTH-01', 'Leather Sandals', 'Hand-stitched leather sandals.', 13990, 'BRL', '5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-000000000115', 'TNT-2P-01', 'Camping Tent 2P', 'Two-person three-season tent.', 59900, 'BRL', '5eed0000-0000-4000-8000-0000000000c3', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-000000000116', 'SLB-3S-01', 'Sleeping Bag', 'Three-season sleeping bag, comfort 2 C.', 34900, 'BRL', '5eed0000-0000-4000-8000-0000000000c3', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-000000000117', 'HLP-300-01', 'Headlamp 300lm', 'Rechargeable 300 lumen headlamp.', 7990, 'BRL', '5eed0000-0000-4000-8000-0000000000c3', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-000000000118', 'BTL-INS-750', 'Insulated Water Bottle', 'Vacuum-insulated bottle, 750 ml.', 6990, 'BRL', '5eed0000-0000-4000-8000-0000000000c3', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-000000000119', 'TRP-ALU-01', 'Trekking Poles', 'Adjustable aluminium trekking poles.', 19990, 'BRL', '5eed0000-0000-4000-8000-0000000000c3', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0),
    ('5eed0000-0000-4000-8000-000000000120', 'STV-CMP-01', 'Camping Stove', 'Compact gas camping stove.', 22990, 'BRL', '5eed0000-0000-4000-8000-0000000000c3', 'active', '2026-10-02T08:00:00Z', '2026-10-02T08:00:00Z', 0)
ON CONFLICT DO NOTHING;

INSERT INTO product_images (id, product_id, url, alt_text, is_primary, position) VALUES
    ('5eed0000-0000-4000-8000-000000000201', '9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01', 'https://cdn.example.test/products/esp-mach-01.jpg', 'Espresso Machine', true, 0),
    ('5eed0000-0000-4000-8000-000000000202', '3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02', 'https://cdn.example.test/products/cb-1kg.jpg', 'Coffee Beans 1kg', true, 0),
    ('5eed0000-0000-4000-8000-000000000203', 'b7e1c4d2-3f58-4a96-8d20-5c1e9a7b3d44', 'https://cdn.example.test/products/mug-cer-01.jpg', 'Ceramic Mug', true, 0),
    ('5eed0000-0000-4000-8000-000000000204', 'a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d', 'https://cdn.example.test/products/ktl-vint-01.jpg', 'Vintage Kettle', true, 0),
    ('5eed0000-0000-4000-8000-000000000205', '5eed0000-0000-4000-8000-000000000105', 'https://cdn.example.test/products/ktl-pour-01.jpg', 'Pour-Over Kettle', true, 0),
    ('5eed0000-0000-4000-8000-000000000206', '5eed0000-0000-4000-8000-000000000106', 'https://cdn.example.test/products/grd-burr-01.jpg', 'Burr Coffee Grinder', true, 0),
    ('5eed0000-0000-4000-8000-000000000207', '5eed0000-0000-4000-8000-000000000107', 'https://cdn.example.test/products/frt-milk-01.jpg', 'Milk Frother', true, 0),
    ('5eed0000-0000-4000-8000-000000000208', '5eed0000-0000-4000-8000-000000000108', 'https://cdn.example.test/products/prs-fr-1l.jpg', 'French Press 1L', true, 0),
    ('5eed0000-0000-4000-8000-000000000209', '0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21', 'https://cdn.example.test/products/trs-001.jpg', 'Trail Running Shoes', true, 0),
    ('5eed0000-0000-4000-8000-000000000210', '5eed0000-0000-4000-8000-000000000110', 'https://cdn.example.test/products/rrs-001.jpg', 'Road Running Shoes', true, 0),
    ('5eed0000-0000-4000-8000-000000000211', '5eed0000-0000-4000-8000-000000000111', 'https://cdn.example.test/products/hkb-001.jpg', 'Hiking Boots', true, 0),
    ('5eed0000-0000-4000-8000-000000000212', '5eed0000-0000-4000-8000-000000000112', 'https://cdn.example.test/products/sck-wool-3.jpg', 'Wool Socks (3 pairs)', true, 0),
    ('5eed0000-0000-4000-8000-000000000213', '5eed0000-0000-4000-8000-000000000113', 'https://cdn.example.test/products/snk-cnv-01.jpg', 'Canvas Sneakers', true, 0),
    ('5eed0000-0000-4000-8000-000000000214', '5eed0000-0000-4000-8000-000000000114', 'https://cdn.example.test/products/snd-lth-01.jpg', 'Leather Sandals', true, 0),
    ('5eed0000-0000-4000-8000-000000000215', '5eed0000-0000-4000-8000-000000000115', 'https://cdn.example.test/products/tnt-2p-01.jpg', 'Camping Tent 2P', true, 0),
    ('5eed0000-0000-4000-8000-000000000216', '5eed0000-0000-4000-8000-000000000116', 'https://cdn.example.test/products/slb-3s-01.jpg', 'Sleeping Bag', true, 0),
    ('5eed0000-0000-4000-8000-000000000217', '5eed0000-0000-4000-8000-000000000117', 'https://cdn.example.test/products/hlp-300-01.jpg', 'Headlamp 300lm', true, 0),
    ('5eed0000-0000-4000-8000-000000000218', '5eed0000-0000-4000-8000-000000000118', 'https://cdn.example.test/products/btl-ins-750.jpg', 'Insulated Water Bottle', true, 0),
    ('5eed0000-0000-4000-8000-000000000219', '5eed0000-0000-4000-8000-000000000119', 'https://cdn.example.test/products/trp-alu-01.jpg', 'Trekking Poles', true, 0),
    ('5eed0000-0000-4000-8000-000000000220', '5eed0000-0000-4000-8000-000000000120', 'https://cdn.example.test/products/stv-cmp-01.jpg', 'Camping Stove', true, 0)
ON CONFLICT DO NOTHING;

INSERT INTO inventory_levels (product_id, on_hand, reserved, version, updated_at) VALUES
    ('9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01', 25, 0, 0, '2026-10-02T08:00:00Z'),
    ('3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02', 100, 0, 0, '2026-10-02T08:00:00Z'),
    ('b7e1c4d2-3f58-4a96-8d20-5c1e9a7b3d44', 0, 0, 0, '2026-10-02T08:00:00Z'),
    ('a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d', 3, 0, 0, '2026-10-02T08:00:00Z'),
    ('5eed0000-0000-4000-8000-000000000105', 15, 0, 0, '2026-10-02T08:00:00Z'),
    ('5eed0000-0000-4000-8000-000000000106', 12, 0, 0, '2026-10-02T08:00:00Z'),
    ('5eed0000-0000-4000-8000-000000000107', 30, 0, 0, '2026-10-02T08:00:00Z'),
    ('5eed0000-0000-4000-8000-000000000108', 20, 0, 0, '2026-10-02T08:00:00Z'),
    ('0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21', 50, 0, 0, '2026-10-02T08:00:00Z'),
    ('5eed0000-0000-4000-8000-000000000110', 40, 0, 0, '2026-10-02T08:00:00Z'),
    ('5eed0000-0000-4000-8000-000000000111', 18, 0, 0, '2026-10-02T08:00:00Z'),
    ('5eed0000-0000-4000-8000-000000000112', 80, 0, 0, '2026-10-02T08:00:00Z'),
    ('5eed0000-0000-4000-8000-000000000113', 25, 0, 0, '2026-10-02T08:00:00Z'),
    ('5eed0000-0000-4000-8000-000000000114', 22, 0, 0, '2026-10-02T08:00:00Z'),
    ('5eed0000-0000-4000-8000-000000000115', 10, 0, 0, '2026-10-02T08:00:00Z'),
    ('5eed0000-0000-4000-8000-000000000116', 14, 0, 0, '2026-10-02T08:00:00Z'),
    ('5eed0000-0000-4000-8000-000000000117', 60, 0, 0, '2026-10-02T08:00:00Z'),
    ('5eed0000-0000-4000-8000-000000000118', 75, 0, 0, '2026-10-02T08:00:00Z'),
    ('5eed0000-0000-4000-8000-000000000119', 16, 0, 0, '2026-10-02T08:00:00Z'),
    ('5eed0000-0000-4000-8000-000000000120', 9, 0, 0, '2026-10-02T08:00:00Z')
ON CONFLICT DO NOTHING;
