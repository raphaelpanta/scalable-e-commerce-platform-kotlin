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

Health (FR-026): `management.endpoint.health.probes.enabled=true` with `show-components` and `show-details` set to
`never`. The liveness group (`livenessState`) and the readiness group (`readinessState`, plus the database contributor
`db` where the service has one: catalog's `HealthProbe` adapter with a two-second timeout; the built-in R2DBC indicator
stays disabled because it has no timeout) answer exactly `{"status":"UP"}` (200) or `{"status":"DOWN"}` (503).
`/actuator/health` answers the aggregate status and the group names, `{"status":"UP","groups":["liveness","readiness"]}`;
clients read only `status`. The image `HEALTHCHECK`, the Compose health check, the CI start-and-health step and the
`platform-probe` health pact use `/actuator/health/readiness`.

| Variable | Used by | Meaning / default |
|---|---|---|
| `<CTX>_DB_HOST`, `<CTX>_DB_USER`, `<CTX>_DB_PASSWORD` | each service | PostgreSQL host and credentials; database name is `<ctx>`, port 5432. Host defaults to `localhost`; credentials have **no committed default**. |
| `KAFKA_BOOTSTRAP_SERVERS` | services | default `localhost:9092`; Compose `kafka:9092` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | all | OTLP/HTTP collector, default `http://localhost:4318`; Compose `http://otel-collector:4318` |
| `JWKS_URI` | gateway, services | default `http://localhost:8080/.well-known/jwks.json`; Compose `http://identity:8080/.well-known/jwks.json` |
| `JWT_ISSUER` | identity, gateway, services | default `https://identity.ecommerce.local` |
| `JWT_AUDIENCE` | identity, gateway, services | default `ecommerce-api` |
| `IDENTITY_URL`, `CATALOG_URL`, `CART_URL`, `ORDER_URL`, `PAYMENT_URL`, `NOTIFICATION_URL` | internal HTTP clients, gateway routes | default `http://localhost:8080`; Compose `http://<ctx>:8080` |
| `IDENTITY_SIGNING_KEY` | identity | Ed25519 PKCS#8 private key (DER, base64) shared by every identity replica; **required** outside the `dev`/`test` profiles; generated into `.env` by the compose scripts |
| `BROWSER_SESSION_KEY` | gateway | AES-256-GCM key sealing the storefront's session and cart cookies (feature 005): 32 random bytes, Base64 (`openssl rand -base64 32`), shared by every gateway replica (rotating it signs every browser out); **required** outside the `dev`/`test` profiles, which generate a per-process key; the gateway refuses to start with a value that does not decode to exactly 32 bytes; generated into `platform/compose/.env` by `scripts/dev-env.sh`, never committed, logged or echoed (`docs/gateway.md`, "Browser session") |
| `STOREFRONT_URL` | gateway | upstream of the `storefront` catch-all route (the static container); default `http://localhost:8082` outside Compose, Compose `http://storefront:8080` |
| `OTEL_COLLECTOR_URL` | gateway | upstream of the browser telemetry routes `telemetry-traces`/`telemetry-logs` (the collector's OTLP/HTTP receiver, no path); default `http://localhost:4318`, Compose `http://otel-collector:4318` |
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
- Errors are RFC 9457 `application/problem+json` using `com.ecommerce.platform.core.problem.Problem` (responses via `com.ecommerce.platform.problem.ProblemResponses`); type URIs are
  `https://ecommerce.example/problems/<slug>` with slugs `validation`, `not-found`, `conflict`, `throttled`,
  `insufficient-stock`, `price-changed`, `stale-revision`, `unauthorized`, `forbidden`, `unavailable` (503), and the
  order-specific `payment-declined` (422), `order-cancelled` (409), `order-not-cancellable` (409),
  `invalid-transition` (409), `idempotency-key-reuse` (422) exactly as `contracts/openapi/order.yaml` uses them; gateway-only `payload-too-large`
  (413) and `internal` (500). Every error
  carries `correlationId`. The public OpenAPI copies use the same host.
- `X-Correlation-Id` is read, sanitised, echoed and logged by the `CorrelationIdWebFilter` of `platform-core`
  (rules in `contracts/gateway-routes.md`); clients copy it to downstream calls and events.
- Routes are built with `com.ecommerce.platform.observability.observedCoRouter` instead of Spring's `coRouter`: handlers
  run with `ReactorThreadLocals`, so their log lines and those of the use cases they call keep `correlationId` and
  `traceId` after a Reactor hop.
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
  the aggregate change; the relay in `platform-messaging` publishes rows to Kafka with the headers `eventId`, `type`,
  `correlationId` and, when the event was written inside a span, the writer's W3C `traceparent` (the consumer's
  listener span continues that trace).
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

## 8. Resolved ambiguities (binding)

Where the design documents disagree, the implementation follows these rules.

| Topic | Rule |
|---|---|
| Checkout hops | Synchronous: order reads the cart (`GET /internal/carts/by-account/{id}`), reserves stock (`POST /internal/reservations`), charges (`POST /internal/charges`), then commits or releases the reservation and clears the cart synchronously. Events (`OrderPlaced`, `OrderPaid`, `OrderPaymentFailed`, `OrderCancelled`) are published too; their consumers in payment, catalog and cart are idempotent safety nets that must converge on the same state (payment keys the charge on the checkout `Idempotency-Key`, so an `OrderPlaced` consumer never creates a second attempt). |
| Checkout responses | 201 `placed`/`approved`; 202 `placed`/`pending` (provider unreachable); 409 `insufficient-stock`; 409 `price-changed`; 409 `order-cancelled` when a cancellation won the race against the charge (order `cancelled`/`failed`, `orderId`, `cancellationReason`); 422 `payment-declined` with the order `cancelled`/`failed` and its `declineReason`. |
| Cart revision mismatch | Any `cartRevision` that is not the cart's current revision is refused with 409 `price-changed`, `changedLines` (possibly empty when only quantities or lines changed) and `currentCartRevision`. `stale-revision` is reserved for optimistic-concurrency conflicts inside a service. |
| Idempotency after a refusal | A refused checkout (409) stores no idempotency record; the shopper may resubmit with the same key or a new one. |
| Cancellation while payment is pending | Shopper or operator cancellation of a `placed` order whose payment is `pending` voids the attempt: `paymentStatus` becomes `failed`, the reservation is released, no refund is recorded. No cancelled order keeps `pending`. |
| Decline categories | `insufficient_funds`, `card_expired`, `card_rejected`, `suspected_fraud`, `invalid_payment_method` everywhere (data-model §2's shorter list is superseded). |
| Event consumers | cart consumes `OrderPaid` (clear ordered lines) and `AccountDeleted`; order consumes `payment.payment.v1` and `AccountDeleted`; order does not need to consume `catalog.stock.v1` in the MVP; notification consumes `RefundRecorded`; catalog consumes `OrderPaid`, `OrderPaymentFailed`, `OrderCancelled`. |
| DNS names | Compose service names are the bare context names (`identity`, `catalog`, ...); the `-service` suffix in gateway-routes.md is superseded. |
| Reservation expiry | 45 minutes after creation (configurable), always later than the 30-minute payment expiry. |
| Seeded operator | `operator@ecommerce.example` / `Operator-Passw0rd!2026` (identity `seed` profile; the acceptance suite reads `OPERATOR_EMAIL`/`OPERATOR_PASSWORD` with these defaults). |
| Module naming | Modules are `domain`, `application`, `infrastructure`; "adapters" in older text means `infrastructure`. |
| Simulated SMS | The notification service's SMS simulator records each message and also mirrors it to Mailpit as an email to `sms-<E.164 digits>@sms.ecommerce.invalid` with subject `SMS to <phone>` and the SMS text as body, so tests and the acceptance suite read codes by searching Mailpit for the phone number. |
| Mailpit chaos | Compose starts Mailpit with `MP_ENABLE_CHAOS=true`; the acceptance suite uses the chaos API to make deliveries fail (US6 scenario 3). |
| Order number | Orders carry `orderNumber` (`ORD-<yyyyMMdd>-<sequence>`); confirmation messages include both the order id and the order number. |
| WebClient in Boot 4 | `WebClient.Builder` is only auto-configured with `spring-boot-starter-webclient`; an infrastructure module that calls another service adds `implementation(libs.spring.boot.starter.webclient)` (alias in the catalogue). |
| Coroutine mutants | The `pitest` convention excludes calls to `kotlin.ResultKt`; application tests' fake ports should `yield()` so resume paths execute. |
| Phone verification code | No event carries it: identity sends the SMS code itself through its own `SmsSenderPort` with a simulated sender that mirrors to Mailpit exactly like the notification service (same recipient and subject rule), so the acceptance suite reads the code from Mailpit by phone number. |
| Notification type values | `order_cancelled`, `refund_confirmation` (types) and `suppressed` (status) are additive values of `contracts/openapi/notification.yaml`. |
