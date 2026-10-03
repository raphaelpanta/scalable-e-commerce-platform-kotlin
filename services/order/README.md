# order

Service for the bounded context **order** (feature 004, FR-011..FR-017): checkout with a synchronous stock
reservation and `Idempotency-Key` handling, order history, shopper cancellation, operator lifecycle transitions, the
payment and account event consumers, the 30-minute payment expiry job and the hourly purge of expired idempotency
records. Public API: `contracts/openapi/order.yaml`.

Package root: `com.ecommerce.order`.

| Module | Contents |
|---|---|
| `domain` | `Order` aggregate (two statuses, ADR 0001), lifecycle and payment rules, checkout decisions, idempotency record |
| `application` | ports and use cases: `PlaceOrder`, `ListOwnOrders`, `GetOwnOrder`, `CancelOwnOrder`, `TransitionOrderStatus`, `ApplyPaymentOutcome`, `RecordRefund`, `AnonymiseAccountOrders`, `ExpirePendingPayments`, `PurgeExpiredIdempotencyRecords` |
| `infrastructure` | coroutine routes under `/api/v1/orders`, R2DBC persistence (`V2__order_schema.sql`), internal WebClients (cart, catalog, payment, identity), outbox publication, Kafka consumers, expiry and idempotency purge jobs |

| Layer | Where | Run alone |
|---|---|---|
| unit | `domain/src/test`, `application/src/test`, architecture rules in `infrastructure` | `./gradlew -q :services:order:domain:test` |
| integration | `infrastructure/src/integrationTest` (PostgreSQL, Kafka, WireMock) | `./gradlew -q :services:order:infrastructure:integrationTest` |
| contract | `infrastructure/src/contractTest` (consumers of cart, catalog, payment, identity; provider `order`) | `./gradlew -q :services:order:infrastructure:contractTest :services:order:infrastructure:contractVerify` |
| acceptance | `infrastructure/src/acceptanceTest` (Cucumber) | `./gradlew -q :services:order:infrastructure:acceptanceTest` |

Running: `ORDER_DB_HOST` (default `localhost`), `ORDER_DB_USER`, `ORDER_DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`,
`CART_URL`, `CATALOG_URL`, `PAYMENT_URL`, `IDENTITY_URL`, `INTERNAL_API_TOKEN`, `JWKS_URI`, then
`./gradlew -q :services:order:infrastructure:bootRun`. Health on `http://localhost:8081/actuator/health`.
