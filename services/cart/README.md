# cart

Service for the bounded context **cart** (user story 2, FR-008..FR-010): anonymous carts addressed by an opaque
`X-Cart-Token` (only its SHA-256 hash is stored) and account carts addressed by the bearer token, priced live from
the catalog's internal pricing API, with an opaque `revision` (ADR 0003), the merge on sign-in (`CartMerged`), the
internal API order uses at checkout (`contracts/internal/cart-internal.yaml`), the `OrderPaid` and `AccountDeleted`
consumers and the hourly purge of anonymous carts idle for 30 days.

Package root: `com.ecommerce.cart`.

| Layer | Where | Run alone |
|---|---|---|
| unit | `domain/src/test`, `application/src/test`, architecture rules in `infrastructure` | `./gradlew -q :services:cart:domain:test` |
| integration | `infrastructure/src/integrationTest` (Testcontainers PostgreSQL) | `./gradlew -q :services:cart:infrastructure:integrationTest` |
| contract | `infrastructure/src/contractTest` (Pact consumer in `contractTest`, provider tagged `provider` in `contractVerify`) | `./gradlew -q :services:cart:infrastructure:contractTest :services:cart:infrastructure:contractVerify` |
| acceptance | `infrastructure/src/acceptanceTest` (Cucumber) | `./gradlew -q :services:cart:infrastructure:acceptanceTest` |

Running: `CART_DB_HOST` (default `localhost`), `CART_DB_USER`, `CART_DB_PASSWORD`, `CATALOG_URL`,
`INTERNAL_API_TOKEN` and `KAFKA_BOOTSTRAP_SERVERS`, then
`./gradlew -q :services:cart:infrastructure:bootRun`. Health on `http://localhost:8081/actuator/health`.
