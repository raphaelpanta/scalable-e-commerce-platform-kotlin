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

## 8. Fresh Podman machine (2026-10-03, after a host crash)

The host crashed twice and the Podman machine had to be recreated (libkrun, 8 CPUs, 11.6 GiB), so every image and
volume was rebuilt from scratch:

| Check | Result |
|---|---|
| `smoke.sh` with `--build` | all seven images rebuilt sequentially and the 20-container stack healthy; every smoke check PASS; 14 min 30 s end to end |
| Journey (register → Mailpit → verify → sign in → address → cart → approved checkout) | 201 `ORD-20261003-0001` placed/approved; confirmation email in Mailpit within 15 s |
| Loki, `{service=~".+"} | correlationId="<id>"` | 26 lines from gateway, identity, cart, catalog, order and payment for the journey's single correlation id (SC-007) |
| Quality gate | every module's `check` green; the relay cancellation leak is fixed in platform-messaging and the cart tests now consume response bodies |

## 9. Cold start with one shared build stage (2026-10-03, T132, SC-006)

`platform/docker/Dockerfile` now compiles the seven boot jars in one Gradle invocation in a `build` stage that does not
depend on `SERVICE_MODULE`; only the `layers` extraction and the runtime stage differ per image
(`platform/docker/README.md`, "Stages"). Machine: Podman 6.0.2 machine (libkrun, 8 CPUs, 11.6 GiB), Docker Compose
v5.6.0, base images already pulled.

Clean state: the stack torn down (`down`, volumes kept), the seven `ecommerce-platform/*:local` images removed and the
build cache pruned with `podman image prune --build-cache -f` (the Gradle cache mount under
`/var/tmp/buildah-cache-501` was recreated empty by the run). Then, from the repository root:

```bash
export BUILDAH_FORMAT=docker COMPOSE_PARALLEL_LIMIT=1 GATEWAY_PORT=18080
docker compose --project-directory platform/compose -f platform/compose/docker-compose.yml --profile core up -d --build
```

| Measure | Before | After |
|---|---|---|
| Shared Gradle stage (all seven jars, Gradle distribution and dependencies downloaded) | one `bootJar` build per image | about 2 min 7 s, once |
| `up -d --build` returned (seven images built, 15 containers started) | 14 min 30 s (section 8) / 24 min (section 1) | 3 min 38 s |
| All seven application containers `healthy` | | **3 min 58 s** |
| Warm rebuild, nothing changed (`compose build`) | about 14 min | 32 s |

Cache behaviour under buildah: the first image (`cart`) ran the Gradle step; for the other six the build output shows
`--> Using cache b4726ec11976...` on the Gradle `RUN` (the same id each time), so no separate base image or build
script was needed. Every image was checked to carry its own application (`Start-Class` of gateway, identity, catalog,
cart, order, payment and notification), the readiness `HEALTHCHECK` and user `app`. The CI path
(`--build-arg APP_JAR=<jar in the context>`) was checked by building the cart image from a pre-built jar: no Gradle
run, `Start-Class` of cart, uid 10001.

**SC-006 is met on this machine**: the cold `--profile core up -d --build` is healthy in under 4 minutes. The floor is
the single Gradle compilation (about 2 minutes) plus the start of the last JVM behind its health check.

## 10. Acceptance suite against the rebuilt stack (2026-10-03, T131)

Stack: the images of section 9, `core` + `observability`, started with the rate-limit override
(`COMPOSE_FILE=docker-compose.yml:../perf/compose.perf.yml platform/compose/scripts/smoke.sh --no-build --keep`, every
smoke check PASS), gateway on 18080. Runner: `GATEWAY_URL=http://localhost:18080 ./gradlew -q :acceptance:test` with
`MAILPIT_URL=http://localhost:8025`, `GRAFANA_URL=http://localhost:3000`, `GRAFANA_PASSWORD` from `.env`, the default
operator credentials.

Final results (scenario outlines expanded; durations are the Gradle wall time):

| Feature | `not @slow and not @chaos` | `@slow and not @chaos` | `@chaos` | Total |
|---|---|---|---|---|
| `account.feature` (US3) | 6 passed | 1 passed | | 7 |
| `authorisation-sweep.feature` (SC-010) | 47 passed | | | 47 |
| `catalogue-browsing.feature` (US1) | 4 passed | | | 4 |
| `catalogue-operations.feature` (US7) | 9 passed | | | 9 |
| `checkout.feature` (US4) | 6 passed | 2 passed | | 8 |
| `notifications.feature` (US6) | | 4 passed | 1 passed | 5 |
| `order-tracking.feature` (US5) | 5 passed | 1 passed | | 6 |
| `platform-observability.feature` (US8) | 2 passed | 3 passed | | 5 |
| `shopping-cart.feature` (US2) | 6 passed | | | 6 |
| **Passed / failed** | **85 / 0** (72 s) | **11 / 0** (147 s) | **1 / 0** (464 s) | **97 / 0** |

How it got there:

| Run | Result | Cause and fix |
|---|---|---|
| fast, 1st | 55 passed, 30 failed | identity was OOM-killed by the kernel (640m limit, heap at 75 %): 503 on every registration afterwards. The image now sizes the heap at 50 % and Compose bounds services at 768m (`platform/docker/Dockerfile`, `platform/compose/docker-compose.yml`). |
| fast, 2nd | 83 passed, 2 failed | (1) "Retrying a checkout with the same idempotency key" never recorded its order, so "the order has exactly 1 payment charge" had none to look up: step defect, fixed in `CheckoutSteps` (the same-order step records it). (2) "The history shows the shopper's own orders" failed its 2nd checkout with 422 "cart is empty": a genuine cart defect. Order clears the cart synchronously and the late `OrderPaid` of the 1st order then took the same product, put back by the shopper for the 2nd order, out of the cart again. The `OrderPaid` consumer now leaves lines added after `paidAt` alone (`services/cart`, domain, application and integration tests; module checks green). |
| fast, 3rd | 85 passed | |
| slow, 1st | 9 passed, 2 failed | both `@sms` scenarios: phone verification answered 503 "The SMS channel is unavailable" because identity's SMS simulator sends through SMTP and Compose gave `SMTP_HOST` only to notification. Identity now gets `SMTP_HOST=mailpit` and waits for Mailpit. |
| slow, 2nd | 11 passed | |
| chaos | 1 passed | Mailpit refused every recipient until the confirmation used up its retries (about 7.5 minutes). |

Also added for the new `withdrawCategory` operation (T136): `Paths.categoryWithdrawal` and a "withdraw a category" row in
both outlines of the authorisation sweep (47 sweep scenarios, all refused as expected). No scenario had to be declared
impossible by contract.

## 11. Performance suite (2026-10-03, T151, SC-002, SC-003)

`platform/perf/seed-10k-apply.sh` then `platform/perf/run.sh --no-seed` (k6 `grafana/k6:1.7.0` in the Podman machine,
reaching the gateway through `host.containers.internal:18080`), the stack of section 10 with the rate-limit override.
The k6 container shares the 8-CPU engine VM with the 20 containers of the stack.

| VUs (browse/checkout) | Browse p95 all / list / search / detail | Browse failed | Orders | Checkout error rate | Stock refusals | Thresholds |
|---|---|---|---|---|---|---|
| 1,000 / 100 (specified) | 5,746 / 5,870 / 5,707 / 5,614 ms | 46.6 % (504) | 137 | 41.0 % (503) | 0 | all 6 FAIL |
| 400 / 40 | 2,340 / 2,487 / 2,412 / 2,089 ms | 0 % | 1,249 | 0 % | 0 | 4 latency FAIL, 2 error PASS |
| 200 / 20 | 552 / 591 / 599 / 477 ms | 0 % | 956 | 0 % | 0 | all 6 PASS |

The gateway serves about 150 requests per second on this machine at every load level: the catalog service is pinned
at its 1-CPU quota and its database at about 2 CPUs. **SC-002/SC-003 are met at 200 browsing + 20 checkout users and
not at the specified 1,000 + 100** on this single machine; the full profile needs a scaled catalogue or more CPU than a
laptop VM shared with the load generator. Details and the reading of the numbers: `platform/perf/README.md`,
"Recorded results".

Defects found and fixed on the way: catalog, cart and Tempo were OOM-killed during the first 1,000-user run (the JVM's
direct buffers defaulted to the heap size; Tempo had 512 MiB): `-XX:MaxDirectMemorySize=128m` in the image and
`TEMPO_MEM_LIMIT` (1g) in Compose; no service restarted in the later runs, Tempo still restarted at 400 users (every
request is traced). The 10k dataset's prices are now multiples of 10, so no order total ends in 13 or 14 (the
simulator's decline rule), which had produced `payment-declined` checkout failures. The dataset was removed again
afterwards (`seed-10k-apply.sh --remove`); the catalogue holds the 20 seed products plus the products the acceptance
scenarios created.
