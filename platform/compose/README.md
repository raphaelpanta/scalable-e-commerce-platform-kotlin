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
| `core` | `gateway`, `identity`, `catalog`, `cart`, `order`, `payment`, `notification`, one PostgreSQL 18 per service (`<ctx>-db`, database `<ctx>`), `kafka` (4.3.1, single-node KRaft, topics auto-created), `mailpit` |
| `observability` | `otel-collector`, `loki`, `tempo`, `prometheus`, `grafana` |
| `ci` | `pact-broker` (3.0.0) with `pact-broker-db` |

Profiles combine: `--profile core --profile observability --profile ci`. The SMS sink is an in-process simulator of the
notification service, so no container is needed for it.

## URLs (the only published ports)

| What | URL |
|---|---|
| Public gateway | http://localhost:8080 |
| Grafana (login `admin` / `GRAFANA_ADMIN_PASSWORD` from `.env`) | http://localhost:3000 |
| Mailpit (email sink; SMTP `mailpit:1025` internal) | http://localhost:8025 |
| Pact Broker (`ci` profile, basic auth from `.env`) | http://localhost:9292 |

Ports are bound to `127.0.0.1` (`BIND_ADDRESS` in `.env` changes that). Services, databases and Kafka publish nothing:
they live on the `internal` network; only the gateway is also on `edge` (FR-023).

## Configuration

| File | Content |
|---|---|
| `.env` (git-ignored, from `.env.example`) | `INTERNAL_API_TOKEN`, `IDENTITY_SIGNING_KEY`, `SEED`, `GRAFANA_ADMIN_PASSWORD`, `PACT_BROKER_*`, `BIND_ADDRESS`, optional `JWT_ISSUER`/`JWT_AUDIENCE` |
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
`INTERNAL_API_TOKEN` (it never routes `/internal/**`).

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

Grafana provisions the data sources Prometheus, Loki and Tempo (logs link to traces through `traceId`, traces link back to
logs) and two dashboards in the folder "E-commerce platform": *Requests by correlation id* and *Service RED*. The RED
dashboard needs the Micrometer histogram buckets: services enable
`management.metrics.distribution.percentiles-histogram.http.server.requests=true`.

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

`docker compose --profile core up -d --build` builds the seven service images from `platform/docker/Dockerfile`.
Their `build` stage compiles all seven boot jars in one Gradle invocation and is identical for every image, so with
`COMPOSE_PARALLEL_LIMIT=1` (set by the scripts) the first image runs Gradle and the six others reuse the stage from the
layer cache: a cold `up --build` takes about 4 minutes on the development machine (`platform/docker/README.md`,
"Start-up time"). The Gradle user home is a cache mount (`sharing=locked`, so concurrent builds wait for each other).
With podman set `BUILDAH_FORMAT=docker` so the `HEALTHCHECK` survives.

## Resource limits

Every service container is bounded (`SERVICE_MEM_LIMIT`, default 768m, and `SERVICE_CPUS`, default 1.0) so that the
JVM's `MaxRAMPercentage=40` sizes the heap to the container (307 MiB) and leaves room for its off-heap memory (an earlier
640m limit with a 75 % heap was OOM-killed under load); databases get `DB_MEM_LIMIT` (256m), Kafka
`KAFKA_MEM_LIMIT` (1g) with `KAFKA_HEAP_OPTS`, and each observability container `OBS_MEM_LIMIT` (512m) except Tempo, `TEMPO_MEM_LIMIT` (2g: every request is
traced, and 512m and 1g were OOM-killed in a loop under the performance suite). The image also caps the JVM's direct
memory and malloc arenas (platform/docker/README.md, "Runtime settings"). The whole
`core` + `observability` stack needs about 9 GiB; on a smaller engine VM start `core` alone first. `GATEWAY_PORT`
moves the published gateway port when 8080 is taken on the host.
