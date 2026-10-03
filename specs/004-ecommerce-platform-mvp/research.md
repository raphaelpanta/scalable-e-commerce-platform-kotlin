# Phase 0 Research: E-Commerce Platform MVP

**Feature**: [spec.md](spec.md) · **Plan**: [plan.md](plan.md) · **Date**: 2026-10-02

All stack questions were settled with the requester on 2026-10-02. Each entry records the
decision, the rationale against the constitution and the spec, and the alternatives considered.
No open clarification items remain.

## 1. API Gateway

- **Decision**: Spring Cloud Gateway (reactive server variant) implemented in Kotlin as the
  `services/gateway` module.
- **Rationale**: Same reactive Kotlin stack as the services, so JWT validation via the identity
  service's JWKS, per-client rate limiting, correlation-id sanitising and deny-by-default routing
  are ordinary Kotlin code covered by unit, integration, contract and mutation tests (Principles
  III, V). Routes are declared in code/config under version control and verified by Pact against
  the identity JWKS endpoint.
- **Alternatives considered**: Traefik (label-driven, zero code; security logic in middleware
  config, weak test coverage); Kong (plugin ecosystem, heavier footprint, separate config store).

## 2. Service Discovery

- **Decision**: Platform-native DNS discovery. Locally, Docker Compose service names resolve to all
  replicas; later, Kubernetes Services do the same. Clients use Spring Cloud LoadBalancer with DNS
  discovery and a low JVM DNS cache TTL (`networkaddress.cache.ttl` = 5 s) so removed instances drop
  out quickly. Retries with backoff on connection errors cover the window between an instance
  disappearing and DNS converging (FR-024, SC-008).
- **Rationale**: Satisfies "add or remove instances without configuration changes" with zero extra
  stateful components, identical behaviour in local, CI and future cluster environments.
- **Alternatives considered**: Consul (health-checked registry, KV, polyglot; one more stateful
  service to run and secure); Eureka (JVM-centric, redundant once the platform provides discovery).

## 3. Observability (Logging, Metrics, Traces)

- **Decision**: OpenTelemetry Collector receiving OTLP from every service; Grafana Loki for logs,
  Prometheus for metrics, Grafana Tempo for traces, Grafana for the single UI. Services emit
  structured JSON logs with `correlationId`, `traceId`, `spanId`, service name and no PII.
  Correlation: W3C `traceparent` propagated by OpenTelemetry; `X-Correlation-Id` accepted at the
  gateway, validated and replaced if malformed with the original recorded in a log field; echoed on every
  response (FR-025, SC-007). The rule has two layers: the gateway accepts a UUID or 16 to 64 characters of
  `[A-Za-z0-9-]` and replaces anything else; the services (platform-core) accept 1 to 64 characters after the
  gateway has sanitised the value, so a call that reaches a service directly (internal calls, tests) may use a shorter id.
- **Rationale**: Lightweight enough for a laptop Compose stack, one UI for logs-to-traces jumps,
  vendor-neutral instrumentation. Meets Principle VI observability requirements.
- **Alternatives considered**: Elastic (ELK) stack (strong full-text search, memory-hungry locally,
  metrics/traces need extra components); OpenSearch stack (same profile as ELK, permissive licence).

## 4. CI/CD Pipeline

- **Decision**: GitHub Actions with a **containerised self-hosted runner** (official
  `actions/runner` image, or `myoung34/github-runner`, chosen at implementation) running on the
  requester's host with the Docker socket mounted so Testcontainers and image builds work. One
  reusable workflow per service triggered by path filters (`services/<name>/**`, `build-logic/**`,
  `gradle/**`), plus a platform workflow for compose/observability changes. Each workflow runs the
  service's full gate (`./gradlew -q :services:<name>:...:check`, Pitest, Pact publish/verify,
  Cucumber for touched journeys) and builds/pushes the image. Required status checks feed the
  pull-request gate of specs/001 and the branch protection of specs/003.
- **Security notes (public repository)**: GitHub's own guidance is that self-hosted runners
  "should almost never be used for public repositories", because any pull request can run code on
  the runner. The requester chose a self-hosted runner knowingly; the plan therefore mandates the
  following mitigations, each of which becomes a task: ephemeral (just-in-time) runner registration
  so every job gets a clean container; the repository setting "require approval for all outside
  collaborators" so fork pull requests never run unattended; workflows triggered only by `push` to
  protected branches and by `pull_request` from collaborators; third-party actions pinned by commit
  SHA; registry credentials only in repository secrets with least privilege; dependency and image
  scanning in the pipeline; no secrets in the runner environment beyond the registration token.
  If these cannot be met, fall back to GitHub-hosted runners for pull-request checks and keep the
  self-hosted runner for post-merge image builds only.
- **Alternatives considered**: GitHub-hosted runners (simplest, declined by requester); GitLab CI
  (would split hosting); Jenkins (highest operational burden, no native PR checks).

## 5. Image Registry

- **Decision**: Private `registry:3` container on the runner host (`platform/ci-runner/`), with
  basic auth and TLS termination, images tagged `<service>:<git-sha>` and `<service>:<branch>`.
- **Consequence**: images are pullable only on that host or network; local developers build
  images from source via Compose (`--build`). Publishing to a hosted registry is deferred to a
  deployment feature.
- **Alternatives considered**: GitHub Container Registry (same account, public pulls); Docker Hub
  (separate account and tokens).

## 6. Container Images

- **Decision**: One parameterised multi-stage Dockerfile, `platform/docker/Dockerfile`, shared by every deployable and
  selected with the build argument `SERVICE_MODULE` (the Gradle path of the module): stage 1 builds with the Gradle
  wrapper (BuildKit cache mounts for Gradle caches, or copies a pre-built jar given as `APP_JAR`), stage 2 extracts
  Spring Boot layers, stage 3 is a minimal JRE runtime image running as a non-root user with a health check and
  `JAVA_TOOL_OPTIONS` for container-aware memory. `.dockerignore`
  excludes build outputs and caches. Base image digests pinned and refreshed by dependency updates.
- **Rationale**: Requester preference; explicit, familiar, scanner-friendly.
- **Alternatives considered**: Jib via convention plugin (no Dockerfile, no daemon at build time,
  reproducible layers); Spring Boot buildpacks (good defaults, needs daemon, less layer control).

## 7. Event Broker and Messaging Patterns

- **Decision**: Apache Kafka in KRaft mode, single node locally (`apache/kafka` image) and via
  Testcontainers in tests. Topics `<context>.<aggregate>.v1`, key = aggregate id, JSON payloads
  described in `contracts/asyncapi/events.yaml`. Producers use the transactional outbox pattern
  (outbox table written in the same transaction as the aggregate, relayed by a poller). Consumers
  are idempotent via a processed-event table keyed by `eventId` (7-day retention) and tolerate
  duplicates and reordering across keys. Schema evolution is additive within `v1`; breaking changes
  create a `v2` topic and a migration window.
- **Rationale**: Durable, replayable log fits order/payment/stock flows and per-service scaling
  with consumer groups (FR-022, Principle VI).
- **Alternatives considered**: Redpanda (Kafka-compatible single binary, smaller community);
  RabbitMQ (simpler routing, no replay, weaker fit for event streams).

## 8. Persistence

- **Decision**: One PostgreSQL instance per service locally (separate containers in Compose, one
  database each), accessed through R2DBC with Spring Data R2DBC repositories implementing outbound
  ports. Schema migrations with Flyway over JDBC executed once at start-up before the service reports
  ready (documented blocking exception in plan.md Complexity Tracking). Optimistic locking on
  aggregates; stock reservation uses a conditional update (`available >= requested`) to guarantee
  non-negative stock under concurrency (FR-012, SC-004).
- **Alternatives considered**: shared PostgreSQL instance with one schema per service (fewer
  containers, but weakens isolation and tempts cross-schema joins); JDBC with virtual threads
  (simpler, but contradicts Principle IV's non-blocking mandate).

## 9. Authentication and Authorisation

- **Decision**: The identity service issues short-lived access tokens (JWT, EdDSA/Ed25519 keys,
  15 min) and rotating refresh tokens (opaque, 30 days, revocable). Gateway and every service
  validate access tokens as OAuth2 resource servers using the identity service's JWKS endpoint;
  roles `shopper` and `operator` are claims. Passwords hashed with Argon2id; sign-in throttled after
  5 consecutive failures per account and per IP (FR-006); verification and reset tokens are random,
  single-use, time-limited (verification 24 h, reset 1 h). Authorisation decisions are enforced in
  the application layer, not only at the gateway (Principle III). Account deletion anonymises PII and
  revokes tokens while orders are kept under a pseudonym (FR-007).
- **Alternatives considered**: external identity provider (Keycloak): robust but heavy for the MVP
  and duplicates the spec's identity bounded context; session cookies: unsuitable for API-first.

## 10. Idempotent Checkout

- **Decision**: `Idempotency-Key` (UUID) header required on order placement; the order service
  stores key, account id, request hash and response for 24 h. Same key + same hash returns the stored
  response; same key + different hash returns 422; concurrent duplicates serialise on the key
  (FR-013, edge cases).
- **Alternatives considered**: client-generated order ids only (does not cover body drift);
  gateway-level deduplication (loses access to the domain result).

## 11. Simulated Payment Provider

- **Decision**: `payment/infrastructure` ships a `SimulatedPaymentProvider` adapter implementing the
  `PaymentProviderPort`. The deterministic rule document is fixed in
  `contracts/openapi/payment.yaml` and readable by operators via `GET /api/v1/payments/simulator/rules`:
  rules evaluated in order, first match wins, default outcome approved: token `tok_sim_unreachable` → provider unreachable (payment status `pending`; the order is cancelled after 30 min); amount minor units ending `13` → declined `insufficient_funds`; ending `14` → declined `card_expired`; token prefix `tok_sim_decline` → declined `card_rejected`; everything else (e.g. `tok_sim_approve_4242`) approved; refunds always succeed. Decline categories shared by all contracts: `insufficient_funds`, `card_expired`,
  `card_rejected`, `suspected_fraud`, `invalid_payment_method`. A real provider later implements the
  same port without touching the order journey (FR-014).
- **Alternatives considered**: a real provider's sandbox (declined by requester for the MVP).

## 12. Notifications

- **Decision**: Notification service consumes identity/order/payment events, renders templates per
  type, and sends through `EmailSenderPort` and `SmsSenderPort`. Locally, email goes to Mailpit
  (SMTP sink with web UI) and SMS to an in-process simulator that records messages. Retries with
  exponential backoff (5 attempts, 30 s → 10 min), then `failed` and visible to operators; dedupe by
  `eventId`; preferences read from the identity service and cached per event (FR-018–020).
- **Alternatives considered**: real providers (Twilio, SendGrid) now: credentials and cost for no
  MVP value; direct SMTP to a mailbox: no inspection UI for tests.

## 13. Testing Toolchain per Layer

- **Unit**: Kotest with property testing (`Arb` generators as fixtures), MockK only for ports at
  application boundaries.
- **Integration**: Testcontainers for PostgreSQL and Kafka; WireMock for external HTTP; Spring
  slices where possible.
- **Contract**: Pact JVM. HTTP pacts for gateway→identity, order→cart, order→catalog, order→payment,
  cart→catalog; message pacts for every event consumer. Pact Broker container on the runner host;
  `can-i-deploy` gates image publication. Matrix in `contracts/pact-matrix.md`.
- **Acceptance**: Cucumber JVM features written from the spec's acceptance scenarios in business
  language, executed against a Compose `core` stack through the gateway; step definitions are the
  only place that knows endpoints.
- **Mutation**: Pitest on `domain` and `application` modules, threshold 80 %, incremental in hooks
  and full in CI; Kotlin support via the available Kotlin plugin (see §14 for the status check).
- **Architecture**: Konsist tests in each `infrastructure` module asserting layer dependencies and
  framework-free domain packages.

## 14. Version Baseline

Versions below are the ones verified at plan time and are pinned in `gradle/libs.versions.toml`
by the build bootstrap feature. Where the constitution names a major version that is no longer
current, a PATCH amendment is proposed rather than pinning an outdated major.

| Item | Version at plan time (2026-10-02) | Note |
|------|-----------------------------------|------|
| JDK | 25 (LTS, GA 2025-09-16; Temurin 25.0.4) | Build and runtime baseline. JDK 27 (installed locally) is a non-LTS feature release and is not used. |
| Kotlin | 2.3.21 (version managed by Spring Boot 4.1); 2.4.20 is the latest stable | Follow Boot's managed Kotlin version to stay inside its tested matrix; move to 2.4.x when a Boot release lists it. K2 is the only compiler. |
| Spring Boot | 4.1.1 GA (Spring Framework 7.0.x); 3.5.16 is the older maintained line | **Constitution amendment proposed**: the stack section says "Spring Boot 3.x"; this plan targets the current GA major. A PATCH amendment ("Spring Boot, latest GA major") should accompany implementation. |
| Spring Cloud | 2025.1.3 (pairs with Boot 4.0.x/4.1.x) | Gateway artifact: `spring-cloud-starter-gateway-server-webflux` (the old `spring-cloud-starter-gateway` is deprecated). |
| Gradle | 9.8.0 | Kotlin DSL default; wrapper pinned by specs/002. |
| Kotest | 6.2.5 | `kotest-runner-junit5`, `kotest-property`. |
| Pitest / Gradle plugin | 1.30.0 / `info.solidsoft.pitest` 1.19.0 | **Kotlin support**: the open-source `pitest-kotlin` plugin is archived (2023); the maintained Kotlin mutator/filter is Arcmutate's `com.arcmutate:pitest-kotlin-plugin` (commercial; open-source licence terms to be confirmed during the build bootstrap). Decision: use Pitest + Arcmutate Kotlin plugin if a licence is obtainable for this public MIT repository; otherwise run Pitest without the plugin with explicit exclusions for Kotlin-synthetic code and keep the 80 % threshold on domain/application packages, documenting the exclusions. |
| Pact JVM / Pact Broker | 4.7.5 / `pactfoundation/pact-broker` 3.0.0 | Broker image is OSS and maintained; 3.0.0 removed the embedded cron. |
| Cucumber JVM | 7.34.x (8.0.3 released 2026-09-28) | Start on the 7.x line; evaluate 8.x once its release notes have settled. |
| Testcontainers | 2.0.5 | `org.testcontainers:testcontainers-kafka` with `KafkaContainer` for `apache/kafka` in KRaft mode. |
| Apache Kafka | 4.3.1 (`apache/kafka` image, KRaft-only) | Single node locally. |
| Grafana Loki / Tempo / OTel Collector contrib / `grafana/otel-lgtm` | 3.7.8 / 3.1.0 / 0.162.0 / 0.35.0 | Separate components in `platform/observability`; `grafana/otel-lgtm` is an acceptable all-in-one for a lighter local profile. |
| Konsist / ArchUnit | 0.17.3 (no release since 2024-12, repo active) / 1.5.1 | Konsist primary; ArchUnit 1.5.1 as fallback if Konsist lags Kotlin 2.4. |
| StrykerJS | 10.0.0 | Frontend only; out of scope here. |
| GitHub Actions runner | `actions/runner` 2.337.0; `myoung34/github-runner` tracks it | See §4 security notes. |

Open follow-ups recorded for `/speckit-tasks`: confirm the Arcmutate licence path; confirm the
published registry name of the official runner image (docs returned 503 during research); pin
Kotlin to Boot's managed version in the catalogue.

## 15. Threat Model

Recorded per service in [plan.md](plan.md) under "Threat Model Summary"; abuse cases map to
OWASP API Security Top-10 categories, and each control is a testable requirement for the task
list (unit tests for rules, integration tests for throttling and ownership checks, acceptance
scenarios for denied access).
