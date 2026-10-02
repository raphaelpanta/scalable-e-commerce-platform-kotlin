# Service conventions (feature 004)

Binding conventions for every module of the platform. Design documents live in
`specs/004-ecommerce-platform-mvp/` (plan, research, data-model, contracts, quickstart); this page fixes the
implementation-level details those documents leave open so that services built in parallel fit together.
When a convention here and a design document disagree, this page wins and the disagreement is a defect to fix.

## 1. Modules and packages

| Module | Gradle path | Convention plugin | Kotlin package root |
|---|---|---|---|
| Service domain | `:services:<ctx>:domain` | `kotlin-domain` | `com.ecommerce.<ctx>.domain` |
| Service application | `:services:<ctx>:application` | `kotlin-application` | `com.ecommerce.<ctx>.application` |
| Service infrastructure | `:services:<ctx>:infrastructure` | `kotlin-service` | `com.ecommerce.<ctx>.infrastructure` |
| Gateway | `:services:gateway` | `kotlin-boot-app` | `com.ecommerce.gateway` |
| Shared core library | `:libs:platform-core` | `kotlin-library` (+ `pitest`) | `com.ecommerce.platform.{problem,correlation,values,security,observability,http,testing}` |
| Shared messaging library | `:libs:platform-messaging` | `kotlin-library` | `com.ecommerce.platform.messaging.{envelope,outbox,consumer}` |
| Acceptance suite | `:acceptance` | `kotlin-library` | `com.ecommerce.acceptance` |

`<ctx>` is one of `identity`, `catalog`, `cart`, `order`, `payment`, `notification`. Domain and application modules
are framework-free (constitution Principle II; enforced by the module graph and the Konsist rules in
`config/architecture`). Shared libraries hold no business rules (Principle VI): value objects with validation only,
plumbing, test fixtures.

Source sets of an infrastructure module: `src/main`, `src/test` (unit, Konsist), `src/integrationTest`
(Testcontainers), `src/contractTest` (Pact), `src/acceptanceTest` (Cucumber against the service alone). The
cross-service Cucumber journeys live in `:acceptance` and run only against a Compose stack (`GATEWAY_URL`).

## 2. Ports, networking and environment

Every service and the gateway listen on **8080** (API) and **8081** (management: `/actuator/health`,
`/actuator/health/readiness`, `/actuator/health/liveness`, `/actuator/prometheus`). Inside Compose the DNS name of a
service is its context name (`identity`, `catalog`, `cart`, `order`, `payment`, `notification`, `gateway`).

| Variable | Used by | Meaning / default |
|---|---|---|
| `<CTX>_DB_HOST`, `<CTX>_DB_USER`, `<CTX>_DB_PASSWORD` | each service | PostgreSQL host and credentials; database name is `<ctx>`, port 5432. Host defaults to `localhost`; credentials have **no committed default**. |
| `KAFKA_BOOTSTRAP_SERVERS` | services | default `localhost:9092`; Compose `kafka:9092` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | all | OTLP/HTTP collector, default `http://localhost:4318`; Compose `http://otel-collector:4318` |
| `JWKS_URI` | gateway, services | default `http://localhost:8080/.well-known/jwks.json`; Compose `http://identity:8080/.well-known/jwks.json` |
| `JWT_ISSUER` | identity, gateway, services | default `https://identity.ecommerce.local` |
| `JWT_AUDIENCE` | identity, gateway, services | default `ecommerce-api` |
| `IDENTITY_URL`, `CATALOG_URL`, `CART_URL`, `ORDER_URL`, `PAYMENT_URL`, `NOTIFICATION_URL` | internal HTTP clients, gateway routes | default `http://localhost:8080`; Compose `http://<ctx>:8080` |
| `INTERNAL_API_TOKEN` | services with `/internal/**` endpoints and their clients | shared secret sent as `X-Internal-Token`; **no committed default** |
| `PLATFORM_CURRENCY` | all | `BRL` |
| `PUBLIC_BASE_URL` | notification | base of links in messages, default `http://localhost:8080` |
| `SMTP_HOST`, `SMTP_PORT` | notification | default `localhost`, `1025` (Mailpit) |
| `SEED` | identity, catalog | `true` activates the Spring profile `seed` (Flyway location `classpath:db/seed`) |

Compose publishes only `gateway:8080`, `grafana:3000` and `mailpit:8025` to the host; services, databases and
Kafka are reachable only on the internal network (FR-023).

## 3. HTTP API rules

- Public paths are versioned `/api/v1/<context>/...` exactly as in `contracts/openapi/*.yaml`; the gateway routes them
  per `contracts/gateway-routes.md`. Anything under `/internal/**` is service-to-service only
  (`contracts/internal/*.yaml`), is never routed by the gateway and requires the `X-Internal-Token` header.
- Errors are RFC 9457 `application/problem+json` using `com.ecommerce.platform.problem.Problem`; type URIs are
  `https://ecommerce.example/problems/<slug>` with slugs `validation`, `not-found`, `conflict`, `throttled`,
  `insufficient-stock`, `price-changed`, `stale-revision`, `unauthorized`, `forbidden`, `unavailable` (503). Every error
  carries `correlationId`. The public OpenAPI copies use the same host.
- `X-Correlation-Id` is read, sanitised, echoed and logged by the `CorrelationIdWebFilter` of `platform-core`
  (rules in `contracts/gateway-routes.md`); clients copy it to downstream calls and events.
- Authentication: bearer JWT (EdDSA / Ed25519, 15 min) issued by identity. Claims: `sub` = account id (UUID),
  `roles` = array of `shopper` | `operator`, `iss` = `JWT_ISSUER`, `aud` = `JWT_AUDIENCE`, `exp`, `iat`, `jti`.
  The gateway **and every service** validate tokens against `JWKS_URI` (resource server from `platform-core`,
  deny by default). Services never call identity per request.
- Paging: `page` (0-based), `size` (1..100, default 20); responses `{items, page, size, totalItems}`.
- Money JSON: `{"amountMinor": 1999, "currency": "BRL"}`.
- Time: ISO-8601 UTC with `Z`.

## 4. Persistence

- One PostgreSQL database per service, R2DBC for requests, Flyway over JDBC only at boot.
- Flyway locations: `classpath:db/migration,classpath:db/messaging` (+ `classpath:db/seed` under the `seed` profile).
  `V1__baseline.sql` comes from the generator; `platform-messaging` ships `db/messaging/V1_1__messaging.sql`
  (tables `outbox`, `processed_event`); service schemas start at `V2__`.
- Optimistic locking: aggregate tables carry `version bigint`; conditional updates for stock
  (`available >= requested`).
- No hard deletes of business records (data-model §1).

## 5. Messaging

- Topics `<context>.<aggregate>.v1`, key = `aggregateId`, JSON envelope per `contracts/asyncapi/events.yaml`
  (`eventId`, `type`, `version`, `occurredAt`, `aggregateId`, `correlationId`, `producer`, `payload`).
- Producers write through `com.ecommerce.platform.messaging.outbox.OutboxPublisher` inside the same transaction as
  the aggregate change; the relay in `platform-messaging` publishes rows to Kafka.
- Consumers wrap handling in `com.ecommerce.platform.messaging.consumer.IdempotentConsumer` (dedupe by `eventId`,
  table `processed_event`, 7-day purge); consumer group id = the context name.
- Kafka images: Testcontainers `org.testcontainers.kafka.KafkaContainer` with the image named in
  `com.ecommerce.platform.testing.Images.KAFKA`; PostgreSQL `Images.POSTGRES`; Mailpit `Images.MAILPIT`.

## 6. Contracts and tests

- Internal endpoints in tests use the token `pact-internal-token` (`INTERNAL_API_TOKEN` set in test setup); pact files
  carry it in `X-Internal-Token`.
- Health pact provider state: `the <ctx> service is running` (the catalog module keeps its historical
  `the catalogue service is running`).
- Images built with podman need `BUILDAH_FORMAT=docker` so the Dockerfile `HEALTHCHECK` survives
  (`platform/docker/README.md`).

- Pacts: consumer name = consuming service (`gateway`, `cart`, `order`, `notification`), provider name = providing
  service. Interactions (description, provider state names, request/response) are fixed in
  `contracts/internal/pact-interactions.md`; consumers and providers implement exactly those names.
- Pact files are written to `<repo>/build/pacts` by every module's `contractTest` task; provider verification
  reads them from there (`@PactFolder` with the `pact.folder` system property set by the `pact` convention),
  is tagged `provider` and runs in the `contractVerify` task after every consumer test of the build.
  `@IgnoreNoPactsToVerify` keeps a provider green while its consumers do not exist yet.
- Test layers follow the generator template (`services/catalog` before feature 004): `@SpringBootTest` with
  `@ServiceConnection` Testcontainers configs, WebTestClient, Kotest assertions, Cucumber JVM suites.
- Mutation: `./gradlew -q :services:<ctx>:domain:pitest` and `:application:pitest` must reach 80 %. Keep use cases
  thin and branch logic in pure domain functions so that property tests kill mutants.

## 7. Build hygiene

- Versions only in `gradle/libs.versions.toml`; module scripts apply a convention plugin and nothing else unless a
  dependency alias is needed.
- `./gradlew -q verify` must stay silent and green; run the module's `check` (and `pitest`) before finishing a task.
- Logging: structured ECS JSON on the console (Spring Boot structured logging), fields `service`, `traceId`,
  `spanId`, `correlationId`; never log emails, phone numbers, addresses, tokens or passwords.
