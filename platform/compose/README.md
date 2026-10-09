# Local platform (Docker Compose)

Everything runs from this directory. Works with `docker compose` (v2 plugin) and `docker-compose`.

```bash
cp .env.example .env                 # once; example values are safe for local use
# once: the identity signing key (required; the scripts below generate it when they create .env)
sed -i.bak "s|^IDENTITY_SIGNING_KEY=.*|IDENTITY_SIGNING_KEY=$(openssl genpkey -algorithm ed25519 -outform DER | base64)|" .env
docker compose --profile core --profile observability up -d --build
docker compose ps                    # wait until every service reports "healthy"
```

The first build downloads Gradle and the dependencies inside the build container (cached by BuildKit afterwards).
Plan for about 8 GB of free memory for containers.

## Profiles

| Profile | Services |
|---|---|
| `core` | `gateway`, `storefront` (the web storefront bundle served by nginx, `platform/docker/Dockerfile.storefront`), `identity`, `catalog`, `cart`, `order`, `payment`, `notification`, one PostgreSQL 18 per service (`<ctx>-db`, database `<ctx>`), `kafka` (4.3.1, single-node KRaft, topics auto-created), `mailpit` |
| `observability` | `otel-collector`, `loki`, `tempo`, `prometheus`, `grafana` |
| `ci` | `pact-broker` (3.0.0) with `pact-broker-db` |

Profiles combine: `--profile core --profile observability --profile ci`. `core` is 16 containers, `core` plus
`observability` 21. The SMS sink is an in-process simulator of the notification service, so no container is needed for
it. The gateway waits for `identity` and `storefront` to be healthy; the storefront is plain nginx and is healthy
within seconds.

## URLs (the only published ports)

| What | URL |
|---|---|
| Public gateway (API under `/api/**`, the web storefront on every other path) | http://localhost:8080 |
| Grafana (login `admin` / `GRAFANA_ADMIN_PASSWORD` from `.env`) | http://localhost:3000 |
| Mailpit (email sink; SMTP `mailpit:1025` internal) | http://localhost:8025 |
| Pact Broker (`ci` profile, basic auth from `.env`) | http://localhost:9292 |

Ports are bound to `127.0.0.1` (`BIND_ADDRESS` in `.env` changes that). Services, databases and Kafka publish nothing:
they live on the `internal` network; only the gateway is also on `edge` (FR-023).

## Configuration

| File | Content |
|---|---|
| `.env` (git-ignored, from `.env.example`) | `INTERNAL_API_TOKEN`, `IDENTITY_SIGNING_KEY`, `BROWSER_SESSION_KEY` (required by the gateway: seals the storefront's session and cart cookies; `scripts/dev-env.sh` and the compose scripts generate it when empty), `SEED`, `GRAFANA_ADMIN_PASSWORD`, `PACT_BROKER_*`, `BIND_ADDRESS`, optional `JWT_ISSUER`/`JWT_AUDIENCE` |
| `env/<ctx>.env` (committed, local-only defaults) | `<CTX>_DB_HOST`, `<CTX>_DB_USER`, `<CTX>_DB_PASSWORD` for the service and `POSTGRES_USER/PASSWORD/DB` for its database; `env/identity.env` also passes `IDENTITY_SIGNING_KEY` from `.env` and raises `IDENTITY_SOURCE_MAX_FAILURES` to 20 (one acceptance runner address) |

`IDENTITY_SIGNING_KEY` is the Ed25519 private key that signs every access token (PKCS#8 DER in Base64). All identity
replicas must share it (FR-024: a token issued by one replica validates against the JWKS served by any other, and
after a restart), so it is not generated per container: identity refuses to start while it is blank. Generate it
once per environment and keep it secret:

```bash
openssl genpkey -algorithm ed25519 -outform DER | base64    # paste the line after IDENTITY_SIGNING_KEY= in .env
```

`scripts/smoke.sh` and `scripts/resilience.sh` (and CI) write a fresh key into a `.env` that has none. Changing the key
invalidates the access tokens issued before it (15 minutes at most); refresh tokens keep working.
| `docker-compose.yml` | shared values of `docs/service-conventions.md` section 2 |

Environment of every service (`identity`, `catalog`, `cart`, `order`, `payment`, `notification`): `env/<ctx>.env`,
`KAFKA_BOOTSTRAP_SERVERS=kafka:9092`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318`,
`JWKS_URI=http://identity:8080/.well-known/jwks.json`, `JWT_ISSUER`, `JWT_AUDIENCE`, `PLATFORM_CURRENCY=BRL`,
`IDENTITY_URL`, `CATALOG_URL`, `CART_URL`, `ORDER_URL`, `PAYMENT_URL`, `NOTIFICATION_URL` (`http://<ctx>:8080`),
`INTERNAL_API_TOKEN`; plus `SEED` (identity, catalog) and `SMTP_HOST=mailpit`, `SMTP_PORT=1025`,
`PUBLIC_BASE_URL=http://localhost:8080` (notification). The gateway gets the same values without Kafka and
`INTERNAL_API_TOKEN` (it never routes `/internal/**`), plus `STOREFRONT_URL=http://storefront:8080` (upstream of the
storefront route), `OTEL_COLLECTOR_URL=http://otel-collector:4318` (browser telemetry pass-through) and
`BROWSER_SESSION_KEY` from `.env`. The storefront container reads no environment.

## Seed data

`SEED=true` (the default in `.env.example`) makes **identity** and **catalog** activate the Spring profile `seed`, so
Flyway also runs `classpath:db/seed` at start-up (categories, products with stock, the operator account, simulator rules).
This flag replaces the one-shot `seed` job mentioned in the quickstart: there is no `seed` container. Seeding is part of
the migration history, hence idempotent across restarts. Set `SEED=false` for an empty platform; a database that was
already seeded keeps its data until it is removed with `down -v`.

## Scaling and rebuilding

```bash
docker compose --profile core up -d --scale catalog=2          # no container_name: replicas are allowed
docker compose --profile core up -d --build catalog            # rebuild and restart one service
docker compose --profile core build --no-cache catalog         # force a clean rebuild
docker compose logs -f catalog
```

Compose DNS resolves a service name to every replica; the JVM caches DNS for 5 s (`networkaddress.cache.ttl=5`) and
Prometheus discovers replicas with `dns_sd_configs`. Services are built from the shared recipe
`platform/docker/Dockerfile` (see `platform/docker/README.md`).

## Observability

| Signal | Path |
|---|---|
| Logs | gateway and services: Logback `OTEL` appender (`logback-spring.xml`) -> OTLP/HTTP -> `otel-collector` -> Loki native OTLP endpoint; the collector copies `service.name` to the label `service`, turns the severity into the label `level` and adds `traceId`/`spanId` from the record's trace context; `correlationId` is structured metadata: `{service=~".+"} \| correlationId="<id>"`. The console keeps ECS JSON (`docker compose logs`) |
| Traces | services (OTLP/HTTP) -> `otel-collector` -> Tempo (OTLP gRPC) |
| Metrics | Prometheus **pulls** `/actuator/prometheus` on port 8081 of `gateway` and the six services (DNS service discovery, every replica); OTLP metrics received by the collector are exposed on `otel-collector:8889` (`prometheus` exporter) and pulled as well; Tempo writes span metrics and service graphs to Prometheus (remote write) |
| Browser telemetry | storefront (OpenTelemetry web SDK, OTLP/HTTP JSON) -> gateway `POST /api/v1/telemetry/v1/{traces,logs}` (anonymous, `browse` rate limit, 256 KiB, cookies and `Authorization` stripped; 503 while the profile is down and the storefront drops the batch) -> `otel-collector` -> Tempo / Loki with resource `service.name=storefront`. The storefront exports route templates, methods, status codes, element roles/ids, durations, error class names, `correlation.id` and a random `session.id` only; the collector is the second privacy layer: a `routing` connector sends `service.name == "storefront"` through `attributes/storefront` (deletes `http.url`, `http.target`, `url.*`, `user.*`, `enduser.*`, headers, `db.*`) and `redaction/storefront` (allow-list of exactly those keys plus `service.*`/`telemetry.sdk.*`; values and log bodies that look like an email, a JWT or a cookie are masked), then the usual Loki labels. The services' pipelines are untouched |

Grafana provisions the data sources Prometheus, Loki and Tempo (logs link to traces through `traceId`, traces link back to
logs) and three dashboards in the folder "E-commerce platform": *Requests by correlation id*, *Service RED* and
*Storefront RUM* (page-load and action p95 by route template from TraceQL metrics, client error rate, throttled or
dropped telemetry exports, recent browser spans linking to *Requests by correlation id*). The RED dashboard needs the
Micrometer histogram buckets: services enable
`management.metrics.distribution.percentiles-histogram.http.server.requests=true`; the RUM dashboard's percentiles are
TraceQL metrics (`quantile_over_time(duration, .95) by (span.http.route)`), computed by Tempo 3 without extra
configuration.

## Scripts

```bash
scripts/smoke.sh [--no-build] [--keep]       # start, wait for health (max 5 min), gateway 200, ports not published
scripts/resilience.sh [--no-build] [--keep]  # catalog=2 stop + scale-up proof, then identity=2 stop under sign-ins
```

`resilience.sh` runs three phases (SC-008, FR-024): catalog with two replicas, one stopped while the catalogue is
browsed every 200 ms (at most `RESILIENCE_GRACE`, default 2, non-2xx after the stop); catalog scaled back to two, where
the new replica must show catalogue requests in its own `http_server_requests_seconds_count` within
`RESILIENCE_PROOF_SECONDS` without more than the grace of non-2xx; then identity with two replicas under wrong-password
sign-ins (401 expected, never a 5xx), one replica stopped, at most `RESILIENCE_POST_GRACE` (default 5) other answers
after the stop and none among the last three. The gateway retries the identity POSTs only when no connection could be
opened, so the tolerated answers come from requests already sent to the stopped replica.

Both are quiet (one PASS/FAIL line per check), create `.env` from the example when it is missing, tear the stack down
(`down -v`) unless `--keep` is given, and exit non-zero on failure.

## Teardown

```bash
docker compose --profile core --profile observability --profile ci down      # keep data volumes
docker compose --profile core --profile observability --profile ci down -v   # also delete all data
```

## Podman

With Podman the images must keep their `HEALTHCHECK`: build in Docker image format (`BUILDAH_FORMAT=docker`), otherwise
services never turn healthy and `depends_on: service_healthy` blocks the stack.

## Building the images

`docker compose --profile core up -d --build` builds the seven service images from `platform/docker/Dockerfile` and
the storefront image from `platform/docker/Dockerfile.storefront` (Node build stage, then nginx; about 30 s cold, see
`platform/docker/README.md`, "Storefront image").
Their `build` stage compiles all seven boot jars in one Gradle invocation and is identical for every image, so with
`COMPOSE_PARALLEL_LIMIT=1` (set by the scripts) the first image runs Gradle and the six others reuse the stage from the
layer cache: a cold `up --build` takes about 4 minutes on the development machine (`platform/docker/README.md`,
"Start-up time"). The Gradle user home is a cache mount (`sharing=locked`, so concurrent builds wait for each other).
With podman set `BUILDAH_FORMAT=docker` so the `HEALTHCHECK` survives. Each JVM image also records an AOT cache in a
short training run at the end of its build (10 to 20 s; `platform/docker/README.md`, "Runtime settings").

**Native images.** The override `compose.native.yml` builds the gateway and the six services as GraalVM native images
(`platform/docker/Dockerfile.native`, tagged `:native`, 256m each) and leaves everything else as it is:

```bash
COMPOSE_PARALLEL_LIMIT=1 docker compose -f docker-compose.yml -f compose.native.yml --profile core up -d --build
```

The first build takes about an hour (one native-image compilation of 6 to 11 minutes per service, a 7.5 GB peak each);
under the performance profile the native stack peaked at about 1.9 GiB against about 3.1 GiB for the JVM images, at the same
throughput
(`platform/docker/README.md`, "Native images" and "Memory measurements"). Add `-f compose.native.yml` to every later
`docker compose` command of that stack; without it, `up -d` switches back to the JVM images.

## Resource limits

Every service container is bounded (`SERVICE_MEM_LIMIT`, default 512m, and `SERVICE_CPUS`, default 1.0) so that the
JVM's `MaxRAMPercentage=50` sizes the heap to the container (256 MiB) and leaves room for its off-heap memory, which the
image's AOT cache and native-heap trimming keep at about 130 to 180 MiB (768m with a 40 % heap before feature 008; an
earlier 640m limit with a 75 % heap was OOM-killed under load). Identity and catalog get 640m (`IDENTITY_MEM_LIMIT`,
`CATALOG_MEM_LIMIT`): identity's Argon2id hashing fills its heap, and catalog serves every browse request; at 512m both
ran within a few tens of MiB of the limit under the performance profile. The GraalVM native images of `compose.native.yml` get
`NATIVE_SERVICE_MEM_LIMIT` (256m). Databases get `DB_MEM_LIMIT` (192m, with 32 MB of shared buffers and at most 50
connections; the services' R2DBC pools start with 2 connections, `R2DBC_POOL_INITIAL_SIZE`, and grow to 10), the native
Kafka broker `KAFKA_MEM_LIMIT` (384m, heap through `KAFKA_OPTS`, default `-Xmx192m`), Mailpit `MAILPIT_MEM_LIMIT` (128m)
and each observability container `OBS_MEM_LIMIT` (512m) except Tempo, `TEMPO_MEM_LIMIT` (2g: every request is traced, and
512m and 1g were OOM-killed in a loop under the performance suite). The Go containers (Mailpit, the collector, Loki,
Tempo, Prometheus, Grafana) get a `GOMEMLIMIT` of about 80 % of their limit (`OBS_GOMEMLIMIT`, `TEMPO_GOMEMLIMIT`,
`MAILPIT_GOMEMLIMIT`; change them together with the limits). The storefront (nginx serving static files) gets
`STOREFRONT_MEM_LIMIT` (64m). See platform/docker/README.md, "Runtime settings" and "Memory measurements". The `core`
profile peaks at about 3.1 GiB under load; the `observability` profile adds about 1.1 GiB at rest, more under load
(Tempo grows with the traced traffic). `GATEWAY_PORT`
moves the published gateway port when 8080 is taken on the host.
