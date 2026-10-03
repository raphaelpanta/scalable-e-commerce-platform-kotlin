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

JOURNEY_PLACEHOLDER

## 4. Observability and resilience (quickstart §5-§6, T102)

OBS_PLACEHOLDER

## 5. Quality gate (quickstart §7)

`./gradlew -q verify` is green and silent for the whole repository (27 modules: build-logic TestKit suite, two
libraries, gateway, six services with four test layers each, acceptance compile). Mutation scores (Pitest, 80 %
floor): platform-core 85 %; cart 97.7/95.8; order 98/86; notification 86/88; payment 90.2/94.7; identity 92/95;
catalog 88.2/92.0 (domain/application).

## 6. Follow-ups recorded

- Register a self-hosted runner and the private registry (`platform/ci-runner`) so the per-service pipelines run.
- Run the performance suite (`platform/perf`) on a machine with headroom; no numbers recorded yet (SC-002, SC-003).
- Revisit the in-memory gateway rate limiter and the absence of Spring Cloud LoadBalancer (DNS + retry) when scaling beyond one gateway instance.
