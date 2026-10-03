# Quickstart run record (2026-10-02)

Execution of [specs/004-ecommerce-platform-mvp/quickstart.md](../../specs/004-ecommerce-platform-mvp/quickstart.md)
on the development machine after the feature-004 implementation (task T121). Environment: macOS, Podman 5.2
(`podman machine`: 4 CPUs, 8 GiB, docker API through a socket shim), 16 GB host already running a dozen unrelated
containers. Everything below was executed by the implementation harness; numbers are from that single run.

## 1. Build and start (quickstart §2, SC-006)

| Step | Result |
|---|---|
| `docker compose --profile core --profile observability up -d --build` | Seven images built from `platform/docker/Dockerfile` in 24 min with `COMPOSE_PARALLEL_LIMIT=1` (sequential builds sharing one Gradle cache mount). The first attempt with parallel builds failed on Gradle lock contention inside the shared cache; fixed with `sharing=locked` and the sequential default (`platform/compose/scripts/smoke.sh`). |
| Service health | All six services and the gateway reached `healthy` once the health check probed `/actuator/health` (the services keep Boot's probe groups off so that body stays the strict `{"status":"UP"}` their pacts assert). |
| Host port | 8080 was held by an unrelated container on this machine; the gateway was published on `GATEWAY_PORT=18080` (new variable). |
| Engine limits | Starting the 20th container hit Podman's per-user kernel keyring quota; the five observability containers were stopped to free it, and memory/CPU limits were added to every container (`SERVICE_MEM_LIMIT` 640m, `KAFKA_MEM_LIMIT` 1g, `DB_MEM_LIMIT` 256m, `OBS_MEM_LIMIT` 512m) after the unbounded JVMs exhausted the 8 GiB VM. |

SC-006 (start under 5 minutes) is **not met on this machine** when images must be built (24 min cold build);
with images present the core stack is healthy in about 90 s.

## 2. Smoke checks (T101, FR-023)

| Check | Result |
|---|---|
| `GET /api/v1/catalog/products?size=3` through the gateway | 200 in 0.18 s, seeded products returned |
| `GET /internal/reservations` through the gateway | 404 `application/problem+json` (deny by default) |
| `GET /api/v1/orders` anonymous | 401 |
| Security headers on a public read | `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `X-Correlation-Id` echoed |
| Published host ports | only the gateway (18080) and Mailpit (8025); Grafana (3000) when the observability profile runs; no database, Kafka or service port reachable from the host (`localhost:5432` refused) |

## 3. Journey (quickstart §4)

Run through the gateway on `http://localhost:18080` with `curl` (seed data loaded, Mailpit on 8025):

| # | Step | Result |
|---|---|---|
| 1 | Register a shopper (`POST /api/v1/identity/accounts`) | 202 (generic, no account enumeration) |
| 2 | Read the verification mail in Mailpit | one message within 8 s, token extracted from the link |
| 3 | Verify (`POST .../accounts/verify-email`) | 204 |
| 4 | Sign in (`POST .../sessions`) | 200 with an EdDSA access token |
| 5 | Add an address (`POST .../accounts/me/addresses`) | 201 |
| 6-8 | Add a seeded product to the account cart, read the cart | 201; cart carries `revision` |
| 9 | Checkout with `tok_sim_approve_4242` | **201** `ORD-20261003-0001`, `orderStatus=placed`, `paymentStatus=approved`, total 65 800 BRL |
| 9b | Same `Idempotency-Key` replayed | 201 with the same order (no duplicate) |
| 10 | Order history | one order, newest first |
| 11 | Cart after the order | empty |
| 12 | Confirmation email | "Order ORD-20261003-0001 confirmed" in Mailpit within 12 s (SC-005) |
| 13 | Shopper cancels the placed order | 200, `cancelled` / `approved`, reason `SHOPPER_REQUEST` |
| 14 | Checkout with `tok_sim_decline_0001` | 422 `payment-declined`, `declineReason=card_rejected` |
| 15 | Checkout with a stale `cartRevision` | 409 `price-changed` with `currentCartRevision` |
| 16 | Simulator rules as operator / as shopper | 200 / 403 |
| 17 | Failed notifications as operator | 200 |

The first checkout attempt with the field names of the quickstart prose (`deliveryAddressId`, `paymentMethodRef`) was
refused with 400 and a field-level problem; the contract (`addressId`, `paymentMethod{type,token}`) is authoritative and
the quickstart wording should be aligned.

## 4. Observability and resilience (quickstart §5-§6, T102)

- **Resilience (SC-008, T102)**: with `catalog` scaled to two replicas, a browse loop every 200 ms kept returning 200
  while one replica was stopped: 60 of 60 requests after the stop succeeded (DNS round-robin with a 5 s TTL plus
  connection-error retry; no Spring Cloud LoadBalancer). To fit the keyring quota, the other services were stopped for
  this check and restarted afterwards.
- **Observability (quickstart §5, SC-007)**: exercised on 2026-10-03 after adding the Logback → OpenTelemetry
  appender (platform-core and gateway `logback-spring.xml`, `opentelemetry-logback-appender-1.0` 2.28.1-alpha) and
  rebuilding all seven images. One fixed `X-Correlation-Id` (`obs-journey-1791018499-b2`) was sent with registration
  → verification (token from Mailpit) → sign-in → add to cart → read cart through the gateway on port 18080. About
  15 s later the Loki data source (queried through Grafana's data source proxy) answered
  `{service=~".+"} | correlationId="obs-journey-1791018499-b2"` with 15 lines from **four services: gateway,
  identity, cart and catalog** (the identity line `GET /internal/accounts/{id}/contact` is the notification
  service's lookup for the verification mail, carrying the same id through Kafka). A second run with anonymous
  catalogue reads and an anonymous cart line returned gateway, catalog and cart.
  - Labels: `/loki/api/v1/labels` lists `level`, `service`, `service_name`; `service` has all seven values.
  - Lines carry `correlationId`, `traceId`, `spanId` and the line's key-value pairs as structured metadata, so the
    query needs no `| json` stage (the quickstart's `| json | correlationId=...` form still returns the same lines,
    marked `__error__=JSONParserErr` because the body is the plain message).
  - Trace linkage: the `traceId` of the identity line `POST /api/v1/identity/accounts 202`
    (`149da01890830685f4d5d89229385f38`) resolves in Tempo to one trace with spans from `gateway` and `identity`.
    The gateway's own access line has no `traceId`: it is written after the request's span has ended.
  - The console output stays ECS JSON (`docker compose logs`), unchanged.
- **Environment ceiling**: rootless Podman limits the number of concurrently running containers through the kernel
  keyring quota (`kernel.keys.maxkeys`); with the unrelated containers already on this machine, the full `core` +
  `observability` stack (20 containers) does not fit. Raise the quota in the Podman machine or stop other containers.

## 5. Quality gate (quickstart §7)

`./gradlew -q verify` is green and silent for the whole repository (27 modules: build-logic TestKit suite, two
libraries, gateway, six services with four test layers each, acceptance compile). Mutation scores (Pitest, 80 %
floor): platform-core 85 %; cart 97.7/95.8; order 98/86; notification 86/88; payment 90.2/94.7; identity 92/95;
catalog 88.2/92.0 (domain/application).

## 6. Follow-ups recorded

- Register a self-hosted runner and the private registry (`platform/ci-runner`) so the per-service pipelines run.
- Run the performance suite (`platform/perf`) on a machine with headroom; no numbers recorded yet (SC-002, SC-003).
- Revisit the in-memory gateway rate limiter and the absence of Spring Cloud LoadBalancer (DNS + retry) when scaling beyond one gateway instance.

## 7. Re-run after resizing the Podman machine (2026-10-03)

The machine was resized from 4 CPUs / 8 GiB to 6 CPUs / 10 GiB (`podman machine set --cpus 6 --memory 10240`), which
also cleared the leaked keyring quota. With the images already built:

| Check | Result |
|---|---|
| `docker compose --profile core --profile observability up -d` | all 20 containers healthy in about 90 s (SC-006 met with warm images) |
| `smoke.sh --no-build` | compose up, health, port isolation PASS; the gateway read raced its own start (000 five seconds after creation) and answered 200 in 0.4 s immediately afterwards |
| Prometheus targets (`up`) | all seven service targets plus the collector report 1 |
| Loki | no log streams at first: the services had no Logback → OpenTelemetry appender, so only traces and metrics were exported; fixed the same day, see section 4 (four services found for one correlation id) |
| Quality gate | every module's `check` green with the engine healthy |
