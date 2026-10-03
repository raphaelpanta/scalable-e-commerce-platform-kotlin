# Running the platform locally

Everything is in `platform/compose`. Prerequisites: Docker (Compose v2) or a compatible engine such as Podman, `curl` and
`jq`. Nothing else has to be installed: the images build the services themselves.

**Memory.** Give the container engine itself, not only the host, about **10 GiB**: the Docker Desktop VM or the Podman
machine (`podman machine set --cpus 6 --memory 10240`). The `core` and `observability` profiles together run 20
containers: seven JVMs bounded at 640 MB each (`SERVICE_MEM_LIMIT`), Kafka at 1 GiB (`KAFKA_MEM_LIMIT`), six PostgreSQL
databases at 256 MB (`DB_MEM_LIMIT`) and the observability tools at 512 MB each (`OBS_MEM_LIMIT`). An 8 GiB engine ran out
of memory; the limits can be lowered in `.env`. Rootless Podman also caps the number of concurrent containers through the
kernel keyring quota (`kernel.keys.maxkeys`): raise it in the machine, or stop unrelated containers, when the 20th
container fails to start.

**Podman.** Build with `BUILDAH_FORMAT=docker` (`export BUILDAH_FORMAT=docker` before `up --build`): Podman's default OCI
image format drops the image `HEALTHCHECK`, so no service would ever report "healthy" and the stack would never finish
starting.

## One-command start

```bash
cd platform/compose
cp .env.example .env     # first time only
export BUILDAH_FORMAT=docker   # Podman only (see above)
docker compose --profile core --profile observability up -d --build
docker compose ps        # every service must become "healthy" (first build: a few minutes)
```

`platform/compose/scripts/smoke.sh` runs the same start-up and checks the result automatically (health, gateway,
unpublished ports) and tears the stack down again.

## Profiles

| Profile | Starts |
|---|---|
| `core` | gateway, identity, catalog, cart, order, payment, notification, one PostgreSQL per service, Kafka, Mailpit |
| `observability` | OpenTelemetry Collector, Loki, Tempo, Prometheus, Grafana |
| `ci` | Pact Broker with its own database (the SMS sink is in-process in the notification service) |

Use `--profile` once per profile you want. `core` alone is enough to use the API.

## Seed data

`SEED=true` in `platform/compose/.env` (default) makes identity and catalog run their Flyway seed scripts at start-up:
3 categories, 20 products with stock, and an operator account. There is no separate seed job. Set `SEED=false` to start
empty; to reseed an already populated stack run `down -v` first.

| Seeded account | Email | Password | Roles |
|---|---|---|---|
| Operator (local development only) | `operator@ecommerce.example` | `Operator-Passw0rd!2026` | shopper, operator (verified) |

Sign in with `POST /api/v1/identity/sessions` to get a bearer token. The payment simulator needs no seed: card tokens
`tok_sim_approve_4242` (approved), `tok_sim_decline_0001` (declined) and `tok_sim_unreachable` (payment stays pending).
The 10,000-product dataset of the performance suite is loaded on demand with `platform/perf/seed-10k-apply.sh`.

## URLs

| What | URL | Notes |
|---|---|---|
| Gateway (only API entry point) | http://localhost:8080 | `/api/v1/<context>/...`; the host port is `GATEWAY_PORT` |
| Grafana | http://localhost:3000 | user `admin`, password `GRAFANA_ADMIN_PASSWORD` from `.env` |
| Mailpit | http://localhost:8025 | emails sent by the notification service |

Services, databases and Kafka are not reachable from the host (FR-023). Pact Broker, when the `ci` profile is on:
http://localhost:9292.

### Another program already uses port 8080

Set `GATEWAY_PORT` in `.env` (the example file has `GATEWAY_PORT=8080`) or in the shell, then use that port everywhere:

```bash
GATEWAY_PORT=18080 docker compose --profile core up -d
GATEWAY_URL=http://localhost:18080 ./gradlew -q :acceptance:test   # the scripts read GATEWAY_PORT / GATEWAY_URL too
```

Links in emails (`PUBLIC_BASE_URL` of the notification service) follow `GATEWAY_PORT`, so they keep working. Grafana (3000)
and Mailpit (8025) have fixed ports.

## Scaling a service

```bash
docker compose --profile core up -d --scale catalog=2
```

Compose DNS returns every replica and the gateway and Prometheus follow them without configuration changes.
`platform/compose/scripts/resilience.sh` stops one of two catalog replicas under load and checks that browsing keeps
working, scales catalog back to two and proves that the new replica receives requests, then repeats the stop for a
POST-heavy service (identity sign-in).

## Inspecting logs by correlation id

Send `-H "X-Correlation-Id: $(uuidgen)"` with a request (the gateway generates one when missing and echoes it in the
response). Then in Grafana open **Dashboards > E-commerce platform > Requests by correlation id**, enter the id and read
the lines of every service; **Open trace in Tempo** on a line jumps to the trace. The same query works in Explore with
the Loki data source: `{service=~".+"} | correlationId="<id>"`. Every service and the gateway send their log lines
as OpenTelemetry log records (Logback `OTEL` appender, `logback-spring.xml`) through the collector to Loki: the body
is the message, `service` and `level` are labels, `correlationId`, `traceId`, `spanId` (and `originalCorrelationId`
when the gateway refused a client value) are structured metadata, so no `| json` stage is needed. Lines can arrive up
to a few seconds after the request (batched export). Per-service request rate, error rate and p95 latency are on the
**Service RED** dashboard. Container logs of one service (ECS JSON): `docker compose logs -f <service>`.

## Rebuild one service

```bash
docker compose --profile core up -d --build order
```

## Teardown

```bash
docker compose --profile core --profile observability --profile ci down       # keep data
docker compose --profile core --profile observability --profile ci down -v    # delete data volumes too
```

## More

- Full validation journey with expected results: `specs/004-ecommerce-platform-mvp/quickstart.md`.
- Compose details, environment and observability data flow: `platform/compose/README.md`.
- Image recipe: `platform/docker/README.md`. Conventions (ports, variables): `docs/service-conventions.md`.

## Performance suite

`platform/perf` holds the k6 load test for SC-002 (catalogue browse and search p95 under 1 s at 10,000 products) and
SC-003 (1,000 concurrent browsing shoppers, 100 concurrent checkouts). Start the stack with the rate-limit override
(`docker compose -f docker-compose.yml -f ../perf/compose.perf.yml --profile core up -d --build` in `platform/compose`),
then `platform/perf/run.sh`: it loads the 10,000-product dataset, runs k6 in a container and writes
`platform/perf/results/summary.json`. Prerequisites, thresholds, how to read the summary and the known limits are in
[platform/perf/README.md](../platform/perf/README.md).
