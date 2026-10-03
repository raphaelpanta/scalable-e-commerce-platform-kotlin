# payment

Service for the bounded context **payment** (feature 004, FR-013, FR-014, FR-016): charges and full refunds through
the simulated provider for the order service, idempotent on `Idempotency-Key`, the payment events, the `OrderPlaced`
and `OrderCancelled` consumers and the public read API of attempts, refunds and simulator rules. Contracts:
`contracts/openapi/payment.yaml` (public) and `contracts/internal/payment-internal.yaml` (internal).

Package root: `com.ecommerce.payment`.

| Module | Contents |
|---|---|
| `domain` | `PaymentAttempt`, `RefundRecord`, value objects, `PaymentProviderPort`, the pure `SimulatedPaymentRules` document |
| `application` | ports and use cases: `AuthoriseCharge`, `RecordRefund`, `ChargeSettlement`, `ChargePlacedOrder`, `SettleCancelledOrder`, `RetryPendingCharges`, `GetPaymentAttempt`, `ListPaymentAttemptsForOrder`, `ListRefundsForOrder`, `GetRefund`, `GetSimulatorRules` |
| `infrastructure` | coroutine routes under `/api/v1/payments` and `/internal`, R2DBC persistence (`V2__payment_schema.sql` to `V5__payment_cancelled_orders.sql`), `SimulatedPaymentProvider`, outbox publication, Kafka consumer of `order.order.v1` (group `payment`), the retry job of pending charges (`payment.retry.*`: delay 60 s, 3 attempts, `PAYMENT_RETRY_DELAY`, `PAYMENT_RETRY_MAX_ATTEMPTS`) |

| Layer | Where | Run alone |
|---|---|---|
| unit | `domain/src/test`, `application/src/test`, adapters and architecture rules in `infrastructure` | `./gradlew -q :services:payment:domain:test` |
| integration | `infrastructure/src/integrationTest` (PostgreSQL, Kafka) | `./gradlew -q :services:payment:infrastructure:integrationTest` |
| contract | `infrastructure/src/contractTest` (consumer of order events; provider `payment` for order and notification) | `./gradlew -q :services:payment:infrastructure:contractTest :services:payment:infrastructure:contractVerify` |
| acceptance | `infrastructure/src/acceptanceTest` (Cucumber) | `./gradlew -q :services:payment:infrastructure:acceptanceTest` |

Running: `PAYMENT_DB_HOST` (default `localhost`), `PAYMENT_DB_USER`, `PAYMENT_DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`,
`INTERNAL_API_TOKEN`, `JWKS_URI`, then `./gradlew -q :services:payment:infrastructure:bootRun`. Health on
`http://localhost:8081/actuator/health`.
