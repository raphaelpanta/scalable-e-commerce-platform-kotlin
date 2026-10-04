# Quickstart: Validate the E-Commerce Platform MVP

This is a validation guide: it starts the platform, walks the shopper and operator journeys through
the public gateway and maps what you should observe to the success criteria in
[spec.md](./spec.md). Schemas are not repeated here; see the contracts
([identity](./contracts/openapi/identity.yaml), [catalog](./contracts/openapi/catalog.yaml),
[cart](./contracts/openapi/cart.yaml), [order](./contracts/openapi/order.yaml),
[payment](./contracts/openapi/payment.yaml), [notification](./contracts/openapi/notification.yaml),
[events](./contracts/asyncapi/events.yaml)) and [data-model.md](./data-model.md).

## 1. Prerequisites

- Docker Engine/Desktop with Compose v2 (`docker compose version`), or Podman with a compose provider, plus `curl`
  and `jq`.
- JDK (current LTS) for the quality gate; the Gradle wrapper is bundled (`./gradlew -q`).
- About 10 GiB of memory for the container engine itself (the Docker Desktop or Podman machine VM, not only the host):
  seven JVMs bounded at 640 MB, Kafka and the observability profile did not fit an 8 GiB machine. Allow a few GiB of
  free disk for the images.

## 2. Start

```bash
cd platform/compose
cp .env.example .env     # first time only; set INTERNAL_API_TOKEN to your own value for anything shared
export BUILDAH_FORMAT=docker   # Podman only: otherwise the image HEALTHCHECK is dropped and nothing becomes "healthy"
docker compose --profile core --profile observability up -d --build
docker compose ps        # wait until every service reports "healthy" (first build: several minutes)
```

| Endpoint | URL |
|----------|-----|
| Public gateway (only entry point) | http://localhost:8080 (`GATEWAY_PORT` in `.env` or the shell moves it, e.g. `GATEWAY_PORT=18080`) |
| Grafana (Explore: Loki, Tempo, Prometheus) | http://localhost:3000 (user `admin`, password `GRAFANA_ADMIN_PASSWORD` from `.env`) |
| Mailpit (email sink) | http://localhost:8025 |

Images are built from the single parameterised `platform/docker/Dockerfile` (one build argument, `SERVICE_MODULE`).
Service ports are not published to the host: any direct call to a service must fail, only the gateway answers (FR-023).
`platform/compose/scripts/smoke.sh` automates this section and checks the result.

The gateway limits sign-in, registration and browsing per source address (`docs/gateway.md`, "Rate limiting"; sign-in 10 per
minute, answered 429 `throttled`). A hand-driven walk-through stays below the limits; for repeated runs or load, start with
`docker compose -f docker-compose.yml -f ../perf/compose.perf.yml --profile core --profile observability up -d --build`.

## 3. Seed data

`SEED=true` (the default of `.env.example`) makes identity and catalog run their Flyway seed scripts at start-up; there
is no separate seed job. The seed holds 3 categories, 20 products with stock, and one verified operator account,
`operator@ecommerce.example` with password `Operator-Passw0rd!2026` (local development only). The simulated payment
provider needs no seed: its card tokens are fixed. To reseed a populated stack run `down -v` first; `SEED=false` starts
empty. The 10,000-product set of the performance suite (SC-002, SC-003) is loaded separately into the running stack with
`platform/perf/seed-10k-apply.sh` (`--remove` deletes it again). The steps below call the seed values
`$OPERATOR_EMAIL`, `$OPERATOR_PASSWORD`, `$APPROVED_METHOD` (card token `tok_sim_approve_4242`) and `$DECLINED_METHOD` (card
token `tok_sim_decline_0001`, declined as `card_rejected`); `tok_sim_unreachable` leaves the payment `pending` and amounts
whose last two minor digits are 13 or 14 are declined. The full rule set is in [payment.yaml](contracts/openapi/payment.yaml)
under `/simulator/rules` (operator only).

```bash
export GW=http://localhost:8080          # http://localhost:$GATEWAY_PORT when you changed the port
export CID=$(uuidgen)   # reuse the same X-Correlation-Id to trace a whole journey
export OPERATOR_EMAIL=operator@ecommerce.example OPERATOR_PASSWORD='Operator-Passw0rd!2026'
export APPROVED_METHOD='{"type":"card","token":"tok_sim_approve_4242"}'
export DECLINED_METHOD='{"type":"card","token":"tok_sim_decline_0001"}'
```

## 4. Journey validation

Paths below are the public API behind the gateway ([gateway-routes.md](contracts/gateway-routes.md)); request and response
bodies follow the OpenAPI contracts. Every call may send `-H "X-Correlation-Id: $CID"` (the gateway accepts a UUID or 16 to 64
characters of `[A-Za-z0-9-]` and replaces anything else); protected calls add `-H "Authorization: Bearer $TOKEN"`; order
placement adds a fresh `Idempotency-Key`. Errors are `application/problem+json`; the problem `type` is a URI ending in the
slug named below.

1. **Register (US3).** `POST $GW/api/v1/identity/accounts` with `{"email", "password", "displayName"}` (the password has 12 characters or more).
   Expect **202** and an unverified account. Registering the same email again also returns 202 with the same generic body,
   without revealing existence.
2. **Read the verification mail.** Open Mailpit (http://localhost:8025); expect one message to that
   address within 30 seconds, containing the verification token or link.
3. **Verify.** `POST $GW/api/v1/identity/accounts/verify-email` with `{"token": "..."}`. Expect 204; the token is
   single-use (a second attempt is refused).
4. **Sign in.** `POST $GW/api/v1/identity/sessions` with `{"email", "password"}`; keep `accessToken` of the answer as
   `$TOKEN`. Use a throw-away account for the throttling check: five wrong passwords in a row make the next attempt
   answer 429 (`throttled`, with `Retry-After`) even with the right password, until the window ends.
5. **Browse anonymously (US1).** `GET $GW/api/v1/catalog/categories`, then
   `GET $GW/api/v1/catalog/products?categoryId=<id>`, then `?q=<term>`, then one product by id, all without `Authorization`.
   Expect paged results with price, image and availability; an unknown id returns 404; a zero-stock product reports out of
   stock.
6. **Anonymous cart (US2).** `POST $GW/api/v1/cart/lines` with `{"productId", "quantity"}` and no token: the cart service issues
   one in the `X-Cart-Token` response header (201); keep it as `$CART_TOKEN` and send it with every later call. Add a second
   product, then `PUT $GW/api/v1/cart/lines/{lineId}` with `{"quantity": n}` to change a quantity and `GET $GW/api/v1/cart`.
   Expect correct line prices, a total and a cart `revision`; quantity 0 removes the line; adding beyond stock is refused with 422
   `insufficient-stock`, stating the available quantity.
7. **Sign in and merge.** Signing in does not touch the cart: after step 4 call
   `POST $GW/api/v1/cart/merge` with both `Authorization` and `X-Cart-Token`. Expect `{cart, cappedLines}`: the account cart
   holds the summed lines capped at stock, with the capping reported.
8. **Add an address.** `POST $GW/api/v1/identity/accounts/me/addresses` (authenticated) with `recipientName`, `line1`, `city`,
   `postalCode` and `countryCode`. Sign out and in again; it is still listed. Attempt `POST $GW/api/v1/orders` while signed
   out: expect 401 and the cart preserved.
9. **Place an approved order (US4).** `GET $GW/api/v1/cart` and note the `revision`. Then `POST $GW/api/v1/orders` with
   `{"cartRevision": "<revision>", "addressId": "<address id>", "paymentMethod": {"type": "card", "token": "tok_sim_approve_4242"}}`
   and `Idempotency-Key: $KEY1`. Stock is reserved synchronously before the payment attempt; expect **201** with the order
   `orderStatus` `placed` and `paymentStatus` `approved`, frozen lines and total at current prices, an emptied cart, and stock
   committed (lowered) by the ordered quantities in the catalogue. (`tok_sim_unreachable` answers 202 with `paymentStatus`
   `pending` instead.)
10. **Place a declined order.** Refill the cart, read its current `revision` and repeat with
    `tok_sim_decline_0001` and a new key. Expect **422** `payment-declined` with `declineReason` and the `orderId` of the order
    recorded as `orderStatus` `cancelled`, `paymentStatus` `failed` and `cancellationReason` `PAYMENT_FAILED`
    (`GET $GW/api/v1/orders/{orderId}`); the stock reservation is released (catalogue stock unchanged) and the cart is intact.
11. **Retry the same key.** Re-send step 9 verbatim (same `Idempotency-Key`, same body). Expect the original
    order returned, with exactly one order and one charge in the history. The same key with a different body answers 422
    `idempotency-key-reuse`.
12. **Stale cart revision after a price change (FR-010, FR-011).** Refill the cart and `GET
    $GW/api/v1/cart`, keeping `revision` as `$REV1`. As the operator, change the price of one
    product in that cart (`PUT $GW/api/v1/catalog/products/{id}` with the full `{name, description, price, categoryId}`).
    As the shopper, send `POST $GW/api/v1/orders` with `cartRevision` set to `$REV1` and a new key. Expect 409 with
    problem type `price-changed` listing `changedLines` (line id, product id, old and new price) and
    `currentCartRevision` (`$REV2`); no order is created and no stock stays reserved. Resubmit with
    `cartRevision` set to `$REV2`: expect the order placed at the current prices (`orderStatus`
    `placed`) with frozen lines and total matching the new price. `GET $GW/api/v1/cart` also
    flags the changed line and returns the new `revision`. (An unreservable line answers 409 `insufficient-stock` with
    `unavailableLines`.)
13. **List history (US5).** `GET $GW/api/v1/orders`: newest first, own orders only, each showing
    `orderStatus`, `paymentStatus` and, when cancelled, the `cancellationReason`. Requesting
    another account's order id returns 404.
14. **Operator advances the order.** Sign in as `$OPERATOR_EMAIL`, then
    `POST $GW/api/v1/orders/{id}/status` with `{"orderStatus": "preparing"}` and then `shipped`; moving to `preparing` is
    allowed only while `paymentStatus` is `approved`. An invalid jump (for example `delivered`
    from `placed`) is refused with 409 `invalid-transition` and leaves the order unchanged.
15. **Cancellation refused after shipping.** As the shopper,
    `POST $GW/api/v1/orders/{id}/cancellation` on the shipped order. Expect 409 `order-not-cancellable` with the
    reason (`cancelled` is reachable only from `placed` or `preparing`). Cancelling a different
    `placed` order with `paymentStatus` `approved` succeeds: `orderStatus` `cancelled` with
    `cancellationReason` `SHOPPER_REQUEST`, the stock returns and a refund is recorded. An
    operator cancelling a `preparing` order records `OPERATOR`; a payment left `pending` for more
    than 30 minutes cancels the order with `PAYMENT_EXPIRED` and releases the stock.
16. **Check notifications (US6).** In Mailpit expect, per event, exactly one email: verification,
    order confirmation (order number, lines, total, address), payment failure, shipping and the
    refund (`GET $GW/api/v1/notifications` lists the shopper's history, SMS included). Re-publishing an event must not create
    a second message.
17. **Authorisation sweep (SC-010).** Call an operator endpoint (`POST /api/v1/catalog/products`)
    with the shopper token (403) and every protected endpoint anonymously (401); all must be denied.

## 5. Observability checks (US8)

1. In Grafana, open Explore, choose the Loki datasource and query
   `{service=~".+"} | correlationId="<CID>"` (or use the dashboard **Requests by correlation id**). Expect entries from at
   least three services (gateway, order, payment, catalog or notification) for the order placed in step 9.
2. Copy a trace id from a log line (or open the Tempo datasource and search by `correlationId`);
   expect one trace spanning the gateway and the downstream services, event consumers included (notification, and
   the catalog and cart consumers of `OrderPaid`): the outbox stores the producer's `traceparent` with each event and
   the relay sends it as a Kafka header, which the consumer's listener span continues.
3. In Prometheus (or the **Service RED** dashboard) confirm request, error and latency series for every
   service. Health is on the management port 8081, reachable only from inside the network, for example
   `docker compose exec catalog bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && cat <&3'`;
   expect `{"status":"UP"}` (the gateway also serves `/actuator/health/readiness` and `/liveness`).
4. Send a request with a malformed `X-Correlation-Id`; expect a replacement id in the response and
   the original value recorded in the gateway log.

## 6. Resilience check (SC-008)

```bash
platform/compose/scripts/resilience.sh --no-build        # automated: replica loss, scale-up, POST phase on identity
# by hand, from platform/compose:
docker compose --profile core up -d --scale catalog=2
# in a second shell, loop catalogue browsing through the gateway:
while true; do curl -s -o /dev/null -w "%{http_code}\n" "$GW/api/v1/catalog/products"; sleep 0.2; done
docker stop $(docker compose --profile core ps -q catalog | head -1)
```

Expect `200` throughout apart from in-flight requests of the stopped instance. Starting a new
instance (`docker rm` the stopped one, then `--scale catalog=2` again) is picked up without configuration changes (FR-024);
`resilience.sh` proves it by reading the new replica's own request counters.

## 7. Quality gate (US9)

```bash
./gradlew -q verify                       # the whole gate: ktlint, detekt, test layers, architecture rules, Pitest
./gradlew -q :services:cart:infrastructure:test :services:cart:infrastructure:integrationTest   # one layer of one service
./gradlew -q contractTest contractVerify  # Pact consumers write build/pacts, then the providers verify them
GATEWAY_URL=$GW ./gradlew -q :acceptance:test   # Cucumber journeys against the running stack (tags: docs/acceptance.md)
```

Run from the repository root; a single-service change runs only that service's pipeline (`.github/workflows/<service>.yml`,
`docs/ci-cd.md`). A deliberately broken contract must fail naming the contract and consumer.

## 8. Expected outcomes

| Criterion | Where to observe it |
|-----------|---------------------|
| SC-001 full journey under 5 minutes | Steps 1-9 timed by a tester following this guide |
| SC-002 browse p95 < 1 s at 10,000 products | Step 5 on the seeded catalogue (20 products; load the 10,000 with `platform/perf/seed-10k-apply.sh`); load script `platform/perf/run.sh` |
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
docker compose --profile core --profile observability --profile ci down -v   # -v also removes the per-service databases
```
