# catalog

Service for the bounded context **catalogue** (user stories 1 and 7, FR-001..FR-003, FR-012): anonymous browsing and
search of products and categories, operator maintenance of products, categories, images and stock (attributed,
audited), the internal reservation and pricing API used by order and cart (`contracts/internal/catalog-internal.yaml`,
ADR 0002), the stock events `StockReserved`, `StockCommitted` and `StockReservationReleased` on `catalog.stock.v1`
(transactional outbox) and the `OrderPaid`/`OrderPaymentFailed`/`OrderCancelled` consumers (group `catalog`). It was
the feature-002 reference service and still is the layout the service generator copies.

Package root: `com.ecommerce.catalog`.

## Modules

| Module | Contents |
|---|---|
| `domain` | value objects (`Money`, `Sku`, `ProductName`, `CategoryName`, `Description`, `ImageRef`, `StockLevel`, `StockAdjustmentReason`, `Quantity`, `SearchTerm`, `PageRequest`), aggregates `Product`, `Category` (+ `CategoryTree`), `InventoryLevel`, `Reservation`, `StockAdjustment`, errors `CatalogError`; health `HealthStatus`, `ServiceName` |
| `application` | ports (`Ports.kt`), the `Catalog` context with the operator check (`Caller`, `authorize`), queries `ListProducts`, `SearchProducts`, `GetProduct`, `ListCategories`, `GetCategory`, pricing `GetPricing`, `GetPricingBatch`, reservations `ReserveStock`, `CommitReservation`, `ReleaseReservation`, `SettleOrderReservation`, `ExpireReservations`, operator use cases in `admin/` |
| `infrastructure` | WebFlux coroutine router (`web/`), R2DBC adapters with guarded stock updates (`persistence/`), outbox publisher and order-event listener (`messaging/`), reservation expiry job (`jobs/`), audit log (`audit/`), Flyway `V2`..`V4` and the seed |

## HTTP surface

| Operation | Path | Use case | Who |
|---|---|---|---|
| listProducts | `GET /api/v1/catalog/products` (`q`, `categoryId`, `includeWithdrawn`, `page`, `size`) | `ListProducts` / `SearchProducts` (with `q`) | anyone |
| getProduct | `GET /api/v1/catalog/products/{productId}` | `GetProduct` | anyone |
| createProduct | `POST /api/v1/catalog/products` | `CreateProduct` | operator |
| updateProduct | `PUT /api/v1/catalog/products/{productId}` | `UpdateProduct` | operator |
| withdrawProduct | `POST /api/v1/catalog/products/{productId}/withdrawal` | `WithdrawProduct` | operator |
| adjustStock | `POST /api/v1/catalog/products/{productId}/stock-adjustments` | `AdjustStock` | operator |
| addProductImage | `POST /api/v1/catalog/products/{productId}/images` | `AddProductImage` | operator |
| listCategories | `GET /api/v1/catalog/categories` (`parentId`, `page`, `size`) | `ListCategories` | anyone |
| getCategory | `GET /api/v1/catalog/categories/{categoryId}` | `GetCategory` | anyone |
| createCategory | `POST /api/v1/catalog/categories` | `CreateCategory` | operator |
| updateCategory | `PUT /api/v1/catalog/categories/{categoryId}` | `UpdateCategory` | operator |
| reserveStock | `POST /internal/reservations` | `ReserveStock` | `X-Internal-Token` |
| commitReservation | `POST /internal/reservations/{reservationId}/commit` | `CommitReservation` | `X-Internal-Token` |
| releaseReservation | `POST /internal/reservations/{reservationId}/release` | `ReleaseReservation` | `X-Internal-Token` |
| getProductPricing | `GET /internal/products/{productId}/pricing` | `GetPricing` | `X-Internal-Token` |
| getProductsPricing | `POST /internal/products/pricing` | `GetPricingBatch` | `X-Internal-Token` |

Writes without a token answer 401 (platform security); a token without the operator role answers 403 and the
refused attempt is logged by `com.ecommerce.catalog.audit` (`audit.outcome`, `audit.action`, `audit.target`,
`audit.accountId`, `audit.correlationId`; no personal data). `availability.availableQuantity` and withdrawn products
are shown to operators only. Search is case-insensitive over name and description, ranked: whole name, name prefix,
name, description. Validation problems are 422 on the public API (400 for malformed requests and query parameters)
and 400 on the internal API.

Reservations: all or nothing, one per order (a replay answers 200 with the stored reservation), unknown and withdrawn
products count as 0 available, every line reserved with
`UPDATE inventory_levels SET reserved = reserved + :q WHERE product_id = :id AND on_hand - reserved >= :q` in one
transaction. Commit and release are idempotent; commit after release and release after commit answer 409. Restocking a
committed reservation follows `OrderCancelled`. Reservations still `reserved` 45 minutes after creation
(`catalog.reservation-ttl`) are released by the expiry job (`StockReservationReleased`, reason `EXPIRED`); the
synchronous release reports reason `CANCELLED`.

## Seed data (`SEED=true`, profile `seed`)

`db/seed/R__seed.sql` (repeatable migration, idempotent) loads three categories and twenty products, each with one
primary image (`https://cdn.example.test/products/<sku>.jpg`). Prices in BRL minor units.

| Category | Id |
|---|---|
| Kitchen & Coffee | `5eed0000-0000-4000-8000-0000000000c1` |
| Footwear | `5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45` |
| Outdoor | `5eed0000-0000-4000-8000-0000000000c3` |

| Product | SKU | Id | Category | Price | Stock | State |
|---|---|---|---|---|---|---|
| Espresso Machine | `ESP-MACH-01` | `9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01` | Kitchen & Coffee | 14900 | 25 | active |
| Coffee Beans 1kg | `CB-1KG` | `3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02` | Kitchen & Coffee | 2450 | 100 | active |
| Ceramic Mug | `MUG-CER-01` | `b7e1c4d2-3f58-4a96-8d20-5c1e9a7b3d44` | Kitchen & Coffee | 3290 | **0** | active (zero stock) |
| Vintage Kettle | `KTL-VINT-01` | `a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d` | Kitchen & Coffee | 25900 | 3 | **withdrawn** |
| Pour-Over Kettle | `KTL-POUR-01` | `5eed0000-0000-4000-8000-000000000105` | Kitchen & Coffee | 18900 | 15 | active |
| Burr Coffee Grinder | `GRD-BURR-01` | `5eed0000-0000-4000-8000-000000000106` | Kitchen & Coffee | 32900 | 12 | active |
| Milk Frother | `FRT-MILK-01` | `5eed0000-0000-4000-8000-000000000107` | Kitchen & Coffee | 8990 | 30 | active |
| French Press 1L | `PRS-FR-1L` | `5eed0000-0000-4000-8000-000000000108` | Kitchen & Coffee | 12900 | 20 | active |
| Trail Running Shoes | `TRS-001` | `0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21` | Footwear | 9490 | 50 | active |
| Road Running Shoes | `RRS-001` | `5eed0000-0000-4000-8000-000000000110` | Footwear | 11990 | 40 | active |
| Hiking Boots | `HKB-001` | `5eed0000-0000-4000-8000-000000000111` | Footwear | 24990 | 18 | active |
| Wool Socks (3 pairs) | `SCK-WOOL-3` | `5eed0000-0000-4000-8000-000000000112` | Footwear | 4990 | 80 | active |
| Canvas Sneakers | `SNK-CNV-01` | `5eed0000-0000-4000-8000-000000000113` | Footwear | 15990 | 25 | active |
| Leather Sandals | `SND-LTH-01` | `5eed0000-0000-4000-8000-000000000114` | Footwear | 13990 | 22 | active |
| Camping Tent 2P | `TNT-2P-01` | `5eed0000-0000-4000-8000-000000000115` | Outdoor | 59900 | 10 | active |
| Sleeping Bag | `SLB-3S-01` | `5eed0000-0000-4000-8000-000000000116` | Outdoor | 34900 | 14 | active |
| Headlamp 300lm | `HLP-300-01` | `5eed0000-0000-4000-8000-000000000117` | Outdoor | 7990 | 60 | active |
| Insulated Water Bottle | `BTL-INS-750` | `5eed0000-0000-4000-8000-000000000118` | Outdoor | 6990 | 75 | active |
| Trekking Poles | `TRP-ALU-01` | `5eed0000-0000-4000-8000-000000000119` | Outdoor | 19990 | 16 | active |
| Camping Stove | `STV-CMP-01` | `5eed0000-0000-4000-8000-000000000120` | Outdoor | 22990 | 9 | active |

The products of `contracts/internal/pact-interactions.md` (Espresso Machine, Coffee Beans 1kg, Trail Running Shoes,
Ceramic Mug, Vintage Kettle) keep their fixed ids. The acceptance suite (`acceptance/`) only needs the seed to be
present (`Catalogue.assertSeeded`) and creates the products of each scenario itself; a product with stock is any active
one above, the zero-stock product is Ceramic Mug, the withdrawn one Vintage Kettle.

## Test layers

| Layer | Where | Run alone |
|---|---|---|
| unit | `domain/src/test` (Kotest properties: value objects, `ReservationSpec`, `CatalogOperationsSpec`, categories), `application/src/test` (fake ports), architecture rules in `infrastructure` | `./gradlew -q :services:catalog:domain:test` |
| integration | `infrastructure/src/integrationTest` (Testcontainers PostgreSQL and Kafka): queries and search, reservations and the race for the last unit, operator API, consumers, seed profile, health, metrics | `./gradlew -q :services:catalog:infrastructure:integrationTest` |
| contract | `infrastructure/src/contractTest`: consumer pacts (`platform-probe` health, `catalog` ← `order` events) in `contractTest`; `CatalogProviderVerificationTest` (cart and order pacts) in `contractVerify` | `./gradlew -q :services:catalog:infrastructure:contractTest :services:catalog:infrastructure:contractVerify` |
| acceptance | `infrastructure/src/acceptanceTest` (Cucumber, service status) | `./gradlew -q :services:catalog:infrastructure:acceptanceTest` |

Mutation testing (threshold 80): `./gradlew -q :services:catalog:domain:pitest :services:catalog:application:pitest`.

## Running

| Variable | Meaning |
|---|---|
| `CATALOG_DB_HOST` | PostgreSQL host (port 5432, database `catalog`); defaults to `localhost` |
| `CATALOG_DB_USER`, `CATALOG_DB_PASSWORD` | database credentials for R2DBC and Flyway (no committed default) |
| `INTERNAL_API_TOKEN` | shared secret expected in `X-Internal-Token` on `/internal/**` (no committed default) |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka brokers, default `localhost:9092` |
| `JWKS_URI`, `JWT_ISSUER`, `JWT_AUDIENCE` | access-token validation (platform defaults) |
| `PLATFORM_CURRENCY` | currency of every price, default `BRL` |
| `SEED` | `true` activates the profile `seed` (Flyway `classpath:db/seed`) |

```bash
./gradlew -q :services:catalog:infrastructure:bootRun
```

- Health: `http://localhost:8081/actuator/health` (`{"status":"UP"}` or 503 `{"status":"DOWN"}`, no details)
- Metrics: `http://localhost:8081/actuator/prometheus`
- Logs: ECS JSON on the console; every request line carries `correlationId` (platform-core's
  `CorrelationIdWebFilter`).

Flyway migrates over JDBC once at start-up, before the service reports ready: the single documented
blocking exception to Principle IV. Every request path is non-blocking (WebFlux, R2DBC, coroutines).
