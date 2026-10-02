# Running the platform locally

Everything is in `platform/compose`. Prerequisites: Docker (Compose v2) or a compatible engine, about 8 GB of free
memory for containers, `curl` and `jq`. Nothing else has to be installed: the images build the services themselves.

## One-command start

```bash
cd platform/compose
cp .env.example .env     # first time only
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
categories, products with stock, an operator account and the payment simulator rules. There is no separate seed job.
Set `SEED=false` to start empty; to reseed an already populated stack run `down -v` first.

## URLs

| What | URL | Notes |
|---|---|---|
| Gateway (only API entry point) | http://localhost:8080 | `/api/v1/<context>/...` |
| Grafana | http://localhost:3000 | user `admin`, password `GRAFANA_ADMIN_PASSWORD` from `.env` |
| Mailpit | http://localhost:8025 | emails sent by the notification service |

Services, databases and Kafka are not reachable from the host (FR-023). Pact Broker, when the `ci` profile is on:
http://localhost:9292.

## Scaling a service

```bash
docker compose --profile core up -d --scale catalog=2
```

Compose DNS returns every replica and the gateway and Prometheus follow them without configuration changes.
`platform/compose/scripts/resilience.sh` stops one of two catalog replicas under load and checks that browsing keeps
working.

## Inspecting logs by correlation id

Send `-H "X-Correlation-Id: $(uuidgen)"` with a request (the gateway generates one when missing and echoes it in the
response). Then in Grafana open **Dashboards > E-commerce platform > Requests by correlation id**, enter the id and read
the lines of every service; **Open trace in Tempo** on a line jumps to the trace. The same query works in Explore with
the Loki data source: `{service=~".+"} | json | correlationId="<id>"`. Per-service request rate, error rate and p95
latency are on the **Service RED** dashboard. Container logs of one service: `docker compose logs -f <service>`.

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
