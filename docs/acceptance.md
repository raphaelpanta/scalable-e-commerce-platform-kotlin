# Cross-service acceptance suite

The `:acceptance` module holds the end-to-end journeys of feature 004 (`specs/004-ecommerce-platform-mvp/spec.md`)
as Cucumber features in the shop's business language. The journeys run against a whole platform through the public
gateway only, the same way a client does. Each service also has its own `acceptanceTest` layer for the service on its
own. This suite needs a running stack, so it is skipped unless `GATEWAY_URL` is set. `./gradlew -q verify` still
compiles and lints it.

## Running it

```bash
cd platform/compose
cp -n .env.example .env
docker compose --profile core --profile observability up -d --build   # observability only for @observability
docker compose ps                                                       # wait until every service is healthy
cd ../..
GATEWAY_URL=http://localhost:8080 ./gradlew -q :acceptance:test
```

Select scenarios by tag with the `cucumber.filter.tags` system property. The Gradle `test` task passes it on to the
test JVM:

```bash
GATEWAY_URL=http://localhost:8080 ./gradlew -q :acceptance:test -Dcucumber.filter.tags="@us4 and not @slow"
GATEWAY_URL=http://localhost:8080 ./gradlew -q :acceptance:test -Dcucumber.filter.tags="not @observability and not @chaos and not @sms"
```

The `summary` plugin prints failures only. The task is never up to date, because the result depends on the stack and
not only on the inputs.

### Environment variables

| Variable | Default | Used for |
|---|---|---|
| `GATEWAY_URL` | (none; the suite is skipped without it) | The only entry point used for business calls |
| `MAILPIT_URL` | `http://localhost:8025` | Reading emails (verification, reset, order confirmation) and the chaos triggers |
| `GRAFANA_URL` | `http://localhost:3000` | Loki and Prometheus through Grafana's data source proxy (uids `loki`, `prometheus`) |
| `GRAFANA_USER` / `GRAFANA_PASSWORD` | `admin` / `admin-local-only` | Grafana basic authentication (`GRAFANA_ADMIN_PASSWORD` in `platform/compose/.env`) |
| `LOKI_URL` | `http://localhost:3100` | Direct Loki queries, used only with `LOKI_VIA_GRAFANA=false`. Compose does not publish Loki to the host. |
| `LOKI_VIA_GRAFANA` | `true` | `false` sends log queries to `LOKI_URL` (from inside the network, or with Loki published) |
| `OPERATOR_EMAIL` / `OPERATOR_PASSWORD` | `operator@ecommerce.example` / `Operator-Passw0rd!2026` | The operator account created by the identity seed |
| `PLATFORM_CURRENCY` | `BRL` | Currency of the prices the suite creates |
| `NOTIFICATION_FAILURE_TIMEOUT_MINUTES` | `15` | How long `@chaos` waits for a failing email to run out of retries |
| `PAYMENT_RETRY_TIMEOUT_SECONDS` | `150` | How long the retry scenario waits for the payment service to retry a pending payment (its `PAYMENT_RETRY_DELAY` is 60 s by default) |

The suite never logs credentials or tokens. Response bodies in assertion messages have `accessToken`,
`refreshToken`, `password`, `token` and `code` masked.

### What the stack must provide

- **Seed data** (`SEED=true`, the default). The seed provides the categories and products checked by "Given the
  seeded catalogue", plus the operator account. The identity seed must create the operator with the credentials
  above, or the run must set `OPERATOR_EMAIL`/`OPERATOR_PASSWORD` to match it.
- **Simulated payment provider** card tokens (rules: `GET /api/v1/payments/simulator/rules`, operator only):
  `tok_sim_approve_4242` is approved, `tok_sim_decline_0001` is declined as `card_rejected`,
  `tok_sim_unreachable` leaves the first attempt `pending` (provider unreachable) and the payment service's retry
  (once the attempt is `PAYMENT_RETRY_DELAY` old, 60 s by default) approves it, and `tok_sim_unreachable_forever`
  leaves every attempt `pending`, for scenarios that need the payment to stay pending. Amounts whose last two minor
  digits are `13` or `14` are declined, so every price in the features keeps totals away from those endings.
- **Rate limits.** The gateway's `auth` tier allows 10 credential calls per minute per source address
  (`contracts/gateway-routes.md`). Every scenario registers, verifies and signs in a fresh shopper, so set-up
  calls wait on 429 with `untilNotThrottled`. The suite stays correct but becomes slow; raise the tier in the local
  gateway configuration for faster runs.
- **`@chaos`** needs Mailpit's chaos triggers (`MP_ENABLE_CHAOS=true` on the `mailpit` service). The scenario makes
  Mailpit refuse every recipient, waits until the order confirmation has used up its retries, and restores Mailpit
  in an `@After` hook. The current Compose file does not enable chaos yet.
- **`@sms`** needs the phone verification code to be readable. SMS goes to an in-process simulator in the
  notification service, and no public API returns message bodies. The suite looks for the code in Mailpit, searching
  for the phone number, on the assumption that the simulator mirrors SMS into Mailpit (the quickstart calls Mailpit
  the "email and SMS sink"). SMS deliveries themselves are checked in the notification history (`GET
  /api/v1/notifications`).
- **`@observability`** needs the `observability` profile. Logs are found by correlation id with
  `{service=~".+"} | json | correlationId="<id>"`, the query the Grafana dashboards use. Metrics come from
  Prometheus (`up{job="services"}` and the Micrometer `http_server_requests_seconds_*` series).

## Coverage

| Story | Feature file | Scenarios (with outlines expanded) | Step class |
|---|---|---|---|
| US1 browse the catalogue | `catalogue-browsing.feature` | 4 | `CatalogueSteps` |
| US2 cart and merge on sign-in | `shopping-cart.feature` | 6 | `CartSteps` |
| US3 account | `account.feature` | 7 | `AccountSteps` |
| US4 checkout and payment | `checkout.feature` | 8 | `CheckoutSteps` |
| US5 order tracking | `order-tracking.feature` | 6 | `OrderTrackingSteps` |
| US6 notifications | `notifications.feature` | 5 | `NotificationSteps` |
| US7 catalogue operations | `catalogue-operations.feature` | 9 | `CatalogueOperationsSteps` |
| US8 platform and observability | `platform-observability.feature` | 5 | `ObservabilitySteps` |
| SC-010 authorisation sweep | `authorisation-sweep.feature` | 45 | `AuthorisationSteps` |

US9 (pipeline scope and image publication) is a CI property, not a platform journey. The CI workflows cover it,
not this suite.

Tags: `@us1` to `@us8` per story; `@security` for the sweep; `@slow` for the race and for anything that waits on a
notification or the central log; `@observability`, `@sms` and `@chaos` for the extra infrastructure described
above.

### Gaps and simplifications

These follow from the contracts:

- **US4.7 provider unreachable.** The suite checks that the order stays `placed` with payment `pending`, that a
  resubmission with the same key does not duplicate it, and (`@slow`) that the payment service's own retry of the
  pending charge approves the order later (two attempts: the first `voided`, the retry `approved`). A shopper
  resubmission has to resend the same body (same card token), so it is not what resolves the payment.
- **US4.7 30-minute expiry (`PAYMENT_EXPIRED`, stock released) is not covered.** The window is the order domain's
  constant `Order.PAYMENT_WINDOW` (30 minutes); no property of the order service shortens it, so a scenario would
  have to wait more than 30 minutes. The expiry is covered by the order service's integration test
  (`OrderLifecycleIT`, the expiry job against a clock) and the payment side (the pending attempt voided by
  `OrderCancelled`) by the payment integration tests. Making the window configurable would let a later scenario use
  `tok_sim_unreachable_forever` and wait for the expiry.
- **US6.4 duplicate events.** Events cannot be injected through the public API. The suite resubmits the same
  checkout with the same idempotency key instead, and checks that exactly one confirmation email and one history entry
  exist.
- **US7.2 audit.** No endpoint lists stock adjustments. The audit (who, when, why) is checked on the `adjustStock`
  answer.
- **US7.4 "the attempt is logged".** This is checked in `platform-observability.feature` (`@observability @us7`) as
  log entries carrying the refused request's correlation id.
- **US8.4 instance loss and US8.1 start time** are covered by `platform/compose/scripts/resilience.sh` and `smoke.sh`.
  Readiness and liveness are not reachable from the host, so the suite checks that Prometheus scrapes every service
  (`up == 1`) with request, error and latency series.
- **Confirmation email content.** The contracts have no separate order number, so the suite expects the order id
  in the email. It accepts the total written as `40.00` or `40,00`.

## Conventions for adding scenarios

- Feature files use the language of `spec.md`, with no paths, JSON or status codes. Money is written with two
  decimals (`25.00`) and converted to minor units by the `{money}` parameter type in `CommonSteps`.
- Steps are the only place that know HTTP, together with `support/Paths.kt`, which names every public path after
  its `operationId` in `specs/004-ecommerce-platform-mvp/contracts/openapi/*.yaml`. Paths, headers, fields, status
  codes and problem slugs come from those contracts and nowhere else.
- One step class per feature (`steps/`); actors, products, stock and refusals shared across features live in
  `CommonSteps`. Cucumber's PicoContainer creates one `ScenarioWorld` per scenario and injects it into every step
  class's constructor. State used by a single class stays in a private field of that class.
- Scenarios are independent. Every scenario creates its own shopper (`shopper+<uuid>@ecommerce.example`), category
  and products with known stock and prices, so runs never depend on each other or on leftovers. The operator's
  session is shared across scenarios and renewed every 10 minutes.
- Every request carries the scenario's `X-Correlation-Id`. Requests that are looked up in the central log (checkout,
  authorisation attempts) carry their own id instead.
- Never use `Thread.sleep`. Asynchronous outcomes use `eventually { ... }`, and "nothing more happens" uses
  `remainsTrue { ... }`, both built on Awaitility. Assertions use Kotest matchers. When a status code differs,
  `shouldHaveStatus` prints the redacted response.
- Keep each step small. Parse responses as `JsonNode` with the helpers in `support/Json.kt` (`string`, `items`,
  `minor`, `list`).
