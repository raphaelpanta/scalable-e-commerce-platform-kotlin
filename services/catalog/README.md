# catalog

Reference service for the bounded context **catalogue**. It carries no business logic yet: it exists so
that every convention of the build (module boundaries, the four test layers, architecture rules, mutation
testing, the container image) does real work, and it is the template new services are created from.
Feature 004 grows the catalogue domain in place.

Package root: `com.ecommerce.catalog`.

## Modules

| Module | Convention | Contents | May depend on |
|---|---|---|---|
| `domain` | `kotlin-domain` | `ServiceName` (validated value object, sealed `ServiceNameResult`), `HealthStatus` (`Up`, `Down(reason)`, `combine`) | nothing (pure Kotlin) |
| `application` | `kotlin-application` | port `HealthProbe`, `HealthReport`, use case `CheckServiceHealth` | `domain`, kotlinx-coroutines |
| `infrastructure` | `kotlin-service` | Spring Boot WebFlux app, `DatabaseHealthProbe` (R2DBC `SELECT 1`, 2 s timeout), `HealthIndicatorAdapter`, `CorrelationIdWebFilter`, Flyway migrations | `application`, `domain`, Spring, R2DBC, Flyway |

The module graph is the first guard (`kotlin-domain` and `kotlin-application` fail the build on a forbidden
dependency); the shared Konsist rules in `config/architecture` run in `infrastructure`'s unit layer as the
second.

## Test layers

| Layer | Where | What | Run alone |
|---|---|---|---|
| unit | `domain/src/test`, `application/src/test`, `infrastructure/src/test` (architecture rules) | Kotest property tests, MockK doubles of ports, Konsist | `./gradlew -q :services:catalog:domain:test` (same for `application`, `infrastructure`) |
| integration | `infrastructure/src/integrationTest` | Testcontainers PostgreSQL: health up/down, Flyway baseline, Prometheus metrics, correlation id and JSON log line | `./gradlew -q :services:catalog:infrastructure:integrationTest` |
| contract | `infrastructure/src/contractTest` | Pact: consumer `platform-probe` writes the repository root `build/pacts` (`contractTest`), provider `catalog` verifies it (`contractVerify`) | `./gradlew -q :services:catalog:infrastructure:contractTest :services:catalog:infrastructure:contractVerify` |
| acceptance | `infrastructure/src/acceptanceTest` | Cucumber `features/service-status.feature` against the running application | `./gradlew -q :services:catalog:infrastructure:acceptanceTest` |

Mutation testing (threshold 80): `./gradlew -q :services:catalog:domain:pitest :services:catalog:application:pitest`,
reports in `<module>/build/reports/pitest/`. Everything at once: `./gradlew -q :services:catalog:infrastructure:check`
(plus `check` of `domain` and `application`). The integration, contract and acceptance layers need a running
Docker-compatible daemon.

## Running

Required environment variables (no defaults are committed):

| Variable | Meaning |
|---|---|
| `CATALOG_DB_HOST` | PostgreSQL host (port 5432, database `catalog`); defaults to `localhost` |
| `CATALOG_DB_USER` | database user for R2DBC and Flyway |
| `CATALOG_DB_PASSWORD` | database password for R2DBC and Flyway |

```bash
./gradlew -q :services:catalog:infrastructure:bootRun
```

- Health: `http://localhost:8081/actuator/health` (`{"status":"UP"}` or 503 `{"status":"DOWN"}`, no details)
- Metrics: `http://localhost:8081/actuator/prometheus`
- Logs: ECS JSON on the console; every request line carries `correlationId` (header `X-Correlation-Id`,
  accepted when at most 64 characters of letters, digits and hyphens, otherwise a generated UUID; always echoed).

Flyway migrates over JDBC once at start-up, before the service reports ready: the single documented
blocking exception to Principle IV. Every request path is non-blocking (WebFlux, R2DBC, coroutines).
