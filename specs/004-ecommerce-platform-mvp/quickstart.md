# Quickstart: Validate the E-Commerce Platform MVP

This is a validation guide: it starts the platform, walks the shopper and operator journeys through
the public gateway and maps what you should observe to the success criteria in
[spec.md](./spec.md). Schemas are not repeated here; see the contracts
([identity](./contracts/openapi/identity.yaml), [catalog](./contracts/openapi/catalog.yaml),
[cart](./contracts/openapi/cart.yaml), [order](./contracts/openapi/order.yaml),
[payment](./contracts/openapi/payment.yaml), [notification](./contracts/openapi/notification.yaml),
[events](./contracts/asyncapi/events.yaml)) and [data-model.md](./data-model.md).

## 1. Prerequisites

- Docker Engine/Desktop with Compose v2 (`docker compose version`), `curl` and `jq`.
- JDK (current LTS) for the quality gate; the Gradle wrapper is bundled (`./gradlew -q`).
- About 8 GB of RAM free for containers.

## 2. Start

```bash
cd platform/compose
docker compose --profile core --profile observability up -d --build
docker compose ps        # wait until every service reports "healthy"
```

| Endpoint | URL |
|----------|-----|
| Public gateway (only entry point) | http://localhost:8080 |
| Grafana (Explore: Loki, Tempo, Prometheus) | http://localhost:3000 |
| Mailpit (email and SMS sink) | http://localhost:8025 |

Images are built from multi-stage Dockerfiles. Service ports are not published to the host: any
direct call to a service must fail, only the gateway answers (FR-023).

## 3. Seed data

The `core` profile runs a one-shot `seed` job (documented in the compose README) that loads
categories, about 10,000 products with stock, one operator account and the simulated payment rules.
Re-run on demand with `docker compose --profile core run --rm seed`. Seed credentials and the
simulator approve/decline rules are listed in the seed README; the steps below call them
`$OPERATOR_EMAIL`, `$OPERATOR_PASSWORD`, `$APPROVED_METHOD` (card token `tok_sim_approve_4242`)
and `$DECLINED_METHOD` (card token `tok_sim_decline_0001`, declined as `card_rejected`); the full
rule set is in [payment.yaml](contracts/openapi/payment.yaml) under `/simulator/rules`.

```bash
export GW=http://localhost:8080
export CID=$(uuidgen)   # reuse the same X-Correlation-Id to trace a whole journey
```

## 4. Journey validation

Paths below are the planned public API; request and response bodies follow the OpenAPI contracts.
Every call may send `-H "X-Correlation-Id: $CID"`; protected calls add
`-H "Authorization: Bearer $TOKEN"`; mutating order calls add a fresh `Idempotency-Key`.

1. **Register (US3).** `POST $GW/api/v1/identity/accounts` with email and a policy-compliant
   password. Expect 201 and an unverified account. Registering the same email again returns the
   generic refusal without revealing existence.
2. **Read the verification mail.** Open Mailpit (http://localhost:8025); expect one message to that
   address within 30 seconds, containing the verification token or link.
3. **Verify.** `POST $GW/api/v1/identity/accounts/verify-email` with the token. Expect 204; the token is
   single-use (a second attempt is refused).
4. **Sign in.** `POST $GW/api/v1/identity/sessions`; keep the token as `$TOKEN`. Five wrong
   passwords in a row must produce throttling (429) on the next attempt.
5. **Browse anonymously (US1).** `GET $GW/api/v1/catalog/products?category=...`, then
   `?q=<term>`, then one product by id, all without `Authorization`. Expect paged results with
   price, image and availability; an unknown id returns 404; a zero-stock product reports out of
   stock.
6. **Anonymous cart (US2).** Generate `CART_TOKEN=$(uuidgen)`, then
   `POST $GW/api/v1/cart/lines` twice with `-H "X-Cart-Token: $CART_TOKEN"` for two products, then
   `PATCH` one quantity and `GET $GW/api/v1/cart`. Expect correct line prices, a total and a cart `revision`; setting
   quantity 0 removes the line; adding beyond stock is refused stating the available quantity.
7. **Sign in and merge.** Repeat step 4 passing `X-Cart-Token`, or call
   `POST $GW/api/v1/cart/merge` with both headers. Expect the account cart to hold the summed lines
   capped at stock, with the capping reported.
8. **Add an address.** `POST $GW/api/v1/identity/accounts/me/addresses` (authenticated). Sign out and in again;
   it is still listed. Attempt `POST $GW/api/v1/orders` while signed out: expect 401 and the cart
   preserved.
9. **Place an approved order (US4).** `GET $GW/api/v1/cart` and note the `revision`. Then
   `POST $GW/api/v1/orders` with the address id, `$APPROVED_METHOD`, `cartRevision` set to that
   revision and `Idempotency-Key: $KEY1`. Stock is reserved synchronously before the payment
   attempt; expect the order with `orderStatus` `placed` and `paymentStatus` `approved`, frozen
   lines and total at current prices, an emptied cart, and stock committed (lowered) by the
   ordered quantities in the catalogue.
10. **Place a declined order.** Refill the cart, read its current `revision` and repeat with
    `$DECLINED_METHOD` and a new key. Expect the order to end `orderStatus` `cancelled` with
    `paymentStatus` `failed`, `cancellationReason` `PAYMENT_FAILED` and a decline reason category;
    the stock reservation is released (catalogue stock unchanged) and the cart is intact.
11. **Retry the same key.** Re-send step 9 verbatim (same `Idempotency-Key`). Expect the original
    order returned, with exactly one order and one charge in the history.
12. **Stale cart revision after a price change (FR-010, FR-011).** Refill the cart and `GET
    $GW/api/v1/cart`, keeping `revision` as `$REV1`. As the operator, change the price of one
    product in that cart (`PATCH $GW/api/v1/catalog/products/{id}`). As the shopper, send
    `POST $GW/api/v1/orders` with `cartRevision` set to `$REV1` and a new key. Expect 409 with
    problem type `price-changed` listing the changed line with its old and new price and the
    current revision (`$REV2`); no order is created and no stock stays reserved. Resubmit with
    `cartRevision` set to `$REV2`: expect the order placed at the current prices (`orderStatus`
    `placed`) with frozen lines and total matching the new price. `GET $GW/api/v1/cart` also
    flags the changed line and returns the new `revision`.
13. **List history (US5).** `GET $GW/api/v1/orders`: newest first, own orders only, each showing
    `orderStatus`, `paymentStatus` and, when cancelled, the `cancellationReason`. Requesting
    another account's order id returns 404 or 403.
14. **Operator advances the order.** Sign in as `$OPERATOR_EMAIL`, then
    `POST $GW/api/v1/orders/{id}/status` to `preparing` then `shipped`; moving to `preparing` is
    allowed only while `paymentStatus` is `approved`. An invalid jump (for example `delivered`
    from `placed`) is refused and leaves the order unchanged.
15. **Cancellation refused after shipping.** As the shopper,
    `POST $GW/api/v1/orders/{id}/cancellation` on the shipped order. Expect a refusal with the
    reason (`cancelled` is reachable only from `placed` or `preparing`). Cancelling a different
    `placed` order with `paymentStatus` `approved` succeeds: `orderStatus` `cancelled` with
    `cancellationReason` `SHOPPER_REQUEST`, the stock returns and a refund is recorded. An
    operator cancelling a `preparing` order records `OPERATOR`; a payment left `pending` for more
    than 30 minutes cancels the order with `PAYMENT_EXPIRED` and releases the stock.
16. **Check notifications (US6).** In Mailpit expect, per event, exactly one email: verification,
    order confirmation (order number, lines, total, address), payment failure, shipping and the
    refund. Re-publishing an event must not create a second message.
17. **Authorisation sweep (SC-010).** Call an operator endpoint (`POST /api/v1/catalog/products`)
    with the shopper token and every protected endpoint anonymously; all must be denied (401/403).

## 5. Observability checks (US8)

1. In Grafana, open Explore, choose the Loki datasource and query
   `{service=~".+"} | json | correlationId="<CID>"`. Expect entries from at least three services
   (gateway, order, payment, catalog or notification) for the order placed in step 9.
2. Copy a trace id from a log line (or open the Tempo datasource and search by `correlationId`);
   expect one trace spanning the gateway and the downstream services.
3. In Prometheus (or the provided dashboard) confirm request, error and latency series for every
   service; `curl` each service's readiness and liveness endpoints from inside the network
   (`docker compose exec <svc> ...`) and expect UP.
4. Send a request with a malformed `X-Correlation-Id`; expect a replacement id in the response and
   the original value recorded in the gateway log.

## 6. Resilience check (SC-008)

```bash
docker compose up -d --scale catalog=2
# in a second shell, loop catalogue browsing through the gateway:
while true; do curl -s -o /dev/null -w "%{http_code}\n" "$GW/api/v1/catalog/products"; sleep 0.2; done
docker stop $(docker compose ps -q catalog | head -1)
```

Expect `200` throughout apart from in-flight requests of the stopped instance. Starting a new
instance (`--scale catalog=2` again) is picked up without configuration changes (FR-024).

## 7. Quality gate (US9)

```bash
./gradlew -q check               # unit, integration, architecture and coverage gates per service
./gradlew -q pitest              # mutation threshold (task names are fixed by specs/002)
./gradlew -q pactVerify          # provider verification of consumer contracts
./gradlew -q acceptanceTest      # Cucumber acceptance suite for the journeys above
```

Run from the repository root or inside one service directory; a single-service change must run only
that service's gate. A deliberately broken contract must fail naming the contract and consumer.

## 8. Expected outcomes

| Criterion | Where to observe it |
|-----------|---------------------|
| SC-001 full journey under 5 minutes | Steps 1-9 timed by a tester following this guide |
| SC-002 browse p95 < 1 s at 10,000 products | Step 5 on the seeded catalogue; load script in the performance suite |
| SC-003 1,000 browsers / 100 checkouts | Performance suite; Grafana latency and error panels |
| SC-004 last-unit race, one winner | Concurrent checkout scenario in the Cucumber suite; stock never negative |
| SC-005 notification within 30 s, one per event | Steps 2 and 16; `NotificationSent` events |
| SC-006 start under 5 minutes, all green | Step 2 timed; `docker compose ps` all healthy |
| SC-007 correlation id across services | Section 5 step 1 on sampled requests |
| SC-008 zero errors on instance loss | Section 6 |
| SC-009 single-service pipeline under 15 minutes | Change one service, observe CI scope and duration |
| SC-010 zero unauthorised accesses | Step 17 and the security scenarios in the acceptance suite |

## 9. Teardown

```bash
docker compose --profile core --profile observability down -v   # -v also removes the per-service databases
```
