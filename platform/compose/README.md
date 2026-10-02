# Local platform (Docker Compose)

Everything runs from this directory. Works with `docker compose` (v2 plugin) and `docker-compose`.

```bash
cp .env.example .env                 # once; example values are safe for local use
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
| `.env` (git-ignored, from `.env.example`) | `INTERNAL_API_TOKEN`, `SEED`, `GRAFANA_ADMIN_PASSWORD`, `PACT_BROKER_*`, `BIND_ADDRESS`, optional `JWT_ISSUER`/`JWT_AUDIENCE` |
| `env/<ctx>.env` (committed, local-only defaults) | `<CTX>_DB_HOST`, `<CTX>_DB_USER`, `<CTX>_DB_PASSWORD` for the service and `POSTGRES_USER/PASSWORD/DB` for its database |
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
| Logs | services (OTLP/HTTP) -> `otel-collector` -> Loki native OTLP endpoint; the collector copies `service.name` to `service` and lifts `correlationId`, `traceId`, `spanId` from ECS JSON bodies into attributes |
| Traces | services (OTLP/HTTP) -> `otel-collector` -> Tempo (OTLP gRPC) |
| Metrics | Prometheus **pulls** `/actuator/prometheus` on port 8081 of `gateway` and the six services (DNS service discovery, every replica); OTLP metrics received by the collector are exposed on `otel-collector:8889` (`prometheus` exporter) and pulled as well; Tempo writes span metrics and service graphs to Prometheus (remote write) |

Grafana provisions the data sources Prometheus, Loki and Tempo (logs link to traces through `traceId`, traces link back to
logs) and two dashboards in the folder "E-commerce platform": *Requests by correlation id* and *Service RED*. The RED
dashboard needs the Micrometer histogram buckets: services enable
`management.metrics.distribution.percentiles-histogram.http.server.requests=true`.

## Scripts

```bash
scripts/smoke.sh [--no-build] [--keep]       # start, wait for health (max 5 min), gateway 200, ports not published
scripts/resilience.sh [--no-build] [--keep]  # catalog=2, stop one replica, expect no more than 2 non-2xx
```

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
