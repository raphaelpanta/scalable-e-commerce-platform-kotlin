# Implementation Plan: E-Commerce Platform MVP

**Branch**: `004-ecommerce-platform-mvp` | **Date**: 2026-10-02 (re-planned after clarifications, same day) | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-ecommerce-platform-mvp/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Deliver the API-first MVP of the e-commerce platform as six independently deployable Kotlin
services (identity, catalog, cart, order, payment, notification) behind a Kotlin API gateway,
communicating synchronously through versioned HTTP APIs and asynchronously through Kafka events,
each owning a PostgreSQL database, observable through OpenTelemetry into a Grafana stack, built as
multi-stage container images, orchestrated locally with Docker Compose, and verified per service by
GitHub Actions on a containerised self-hosted runner. Payments use a deterministic simulated
provider; an account is required to check out; orders carry separate order and payment statuses;
the web storefront is out of scope.

Stack decisions were taken with the requester on 2026-10-02 (see [research.md](research.md)):
Spring Cloud Gateway in Kotlin · platform DNS discovery · OpenTelemetry + Grafana LGTM ·
GitHub Actions on a containerised self-hosted runner with a private registry on the runner host ·
one parameterised multi-stage Dockerfile · Apache Kafka in KRaft mode.

## Technical Context

**Language/Version**: Kotlin 2.3.x (the version managed by Spring Boot 4.1; K2 compiler) on JDK 25
LTS; exact versions pinned in `gradle/libs.versions.toml` by the build bootstrap feature
(specs/002). Full baseline in research §14, including a proposed PATCH amendment to the
constitution's "Spring Boot 3.x" wording.

**Primary Dependencies**: Spring Boot 4.1 (Spring Framework 7) with WebFlux + kotlinx-coroutines;
Spring Cloud 2025.1 Gateway (`gateway-server-webflux`) for the gateway; Spring Security resource server (JWT/JWKS); Spring Data
R2DBC + PostgreSQL R2DBC driver; Flyway (startup-only migrations); Spring for Apache Kafka with
reactive/coroutine consumers; Micrometer + OpenTelemetry exporters (logs, metrics, traces);
kotlinx-serialization (or Jackson Kotlin module where Spring requires it); Arrow `Either` for
domain/application error values.

**Storage**: One PostgreSQL database per service (identity, catalog, cart, order, payment,
notification); Kafka topics `<context>.<aggregate>.v1` keyed by aggregate id; outbox tables in each
producing service; processed-event tables in each consumer.

**Testing**: Kotest (property testing as default for domain/application), MockK at adapter edges,
Testcontainers (PostgreSQL, Kafka), WireMock for external HTTP stubs, Pact JVM with a Pact Broker
container (HTTP and message pacts), Cucumber JVM acceptance features in ubiquitous language run
against the gateway, Pitest for mutation testing (≥ 80 % on domain + application; Kotlin plugin
licensing is an open follow-up, research §14), Konsist for architecture rules. Four source sets per service: `test`, `integrationTest`, `contractTest`,
`acceptanceTest` (acceptance also aggregated in `acceptance/`).

**Target Platform**: Linux containers (non-root, JRE runtime stage); local Docker Compose with
profiles `core`, `observability`, `ci`; CI on a containerised GitHub Actions runner with Docker
socket access; images pushed to a private `registry:3` on the runner host.

**Project Type**: Multi-service backend monorepo (API-first). Frontend directory reserved, out of
scope for this feature.

**Performance Goals**: p95 < 1 s for catalogue browse/search at 10,000 products (SC-002);
1,000 concurrent shoppers browsing and 100 concurrent checkouts without errors other than stock
refusals (SC-003); notification attempt within 30 s of an event (SC-005); full local start-up
< 5 min (SC-006).

**Constraints**: Non-blocking I/O end to end (no `runBlocking`/`GlobalScope` in production code;
blocking allowed only for Flyway at boot behind a documented exception); stock never negative under
concurrency (SC-004); idempotent checkout; no PII or secrets in logs; deny-by-default authorisation;
quiet build output; images reproducible from Dockerfiles.

**Scale/Scope**: 7 deployables (6 services + gateway), ~19 Gradle modules (3 per service + gateway),
~45 HTTP operations across 6 OpenAPI contracts, 21 event types on 6 topics, 9 user stories /
29 functional requirements.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Gate | Pre-research | Post-design |
|---|-----------|------|--------------|-------------|
| I | Kotlin-idiomatic monorepo, Gradle Kotlin DSL | All modules via convention plugins + version catalog (specs/002); ktlint + detekt in `check` | PASS (depends on 002) | PASS |
| II | Clean/Hexagonal + DDD | Each service = `domain` / `application` / `infrastructure` modules; value objects for all invariants (data-model.md §2); Konsist rules fail the build on layer violations | PASS | PASS – data model defines aggregates, VOs, state machines |
| III | Security by design | Threat model per service recorded below; deny-by-default at gateway and in application layer; JWT/JWKS, Argon2id, throttling, idempotency keys, rate limits; no PII in logs; dependency scanning in CI | PASS | PASS – contracts mark every operation's auth; Problem+JSON avoids leaking internals |
| IV | Functional & non-blocking | WebFlux + coroutines, R2DBC, reactive Kafka; `Either` results in domain/application; exceptions only at adapters | PASS | PASS – Flyway-at-boot exception justified in Complexity Tracking |
| V | Layered test contract | Property tests default; Testcontainers/WireMock integration; Pact matrix (contracts/pact-matrix.md); Cucumber features behaviour-level; Pitest ≥ 80 % | PASS | PASS – pact matrix and acceptance scenarios cover every inter-service edge |
| VI | Microservice boundaries | One DB per service; async events for cross-context effects (contracts/asyncapi/events.yaml); versioned paths `/api/v1`; health/metrics/traces per service | PASS | PASS |
| VII | TypeScript + React frontend | Not applicable – API-first scope; `frontend/` reserved only | N/A (recorded) | N/A |
| VIII | Token-efficient, hook-driven harness | Hooks in `.claude/hooks/*` active; quiet Gradle; subagents used for this plan's artifacts; mutation in end-of-task gate | PASS | PASS |

No gate failures. Deviations needing justification are listed in Complexity Tracking.

## Threat Model Summary (Principle III)

| Service | Assets | Main abuse cases | Controls (OWASP API Top-10 mapping) |
|---------|--------|------------------|-------------------------------------|
| Gateway | All traffic, tokens in transit | Unauthenticated access, credential stuffing, DoS, header spoofing | JWT validation via JWKS (API2), deny unknown routes (API9), per-client rate limits (API4), correlation-id sanitising, request size limits, security headers |
| Identity | Credentials, PII, sessions | Account enumeration, brute force, token theft, reset abuse | Generic errors, Argon2id, throttling after 5 failures (API2), short-lived access + rotating refresh tokens, single-use time-limited reset (API2), anonymisation on delete (API3) |
| Catalog | Product data, stock | Unauthorised edits, oversell via race, data scraping | Operator role checks in application layer (API5), atomic reservations with row-level guards (API6), pagination caps (API4) |
| Cart | Purchase intent, prices, revision | Cart token guessing, price tampering, stale-revision replay, cross-user cart access | Opaque random cart tokens, server-side prices only, revision validated server-side at checkout, ownership checks (API1) |
| Order | Orders, addresses, totals | Duplicate orders, IDOR on history, illegal status transitions | Idempotency-Key with request hash (API6), ownership checks (API1), lifecycle enforced in aggregate (API6), operator-only transitions (API5) |
| Payment | Payment attempts, refunds | Replay, forged approvals, refund abuse | Idempotent attempts keyed by order, provider abstraction with signed internal calls, refunds only from order cancellation flow (API6) |
| Notification | Contact data, message content | Spam via event replay, PII leakage in logs | Dedupe by eventId, preference checks, templated content, redaction in logs (API3) |
| Platform | Images, CI runner, registry | Malicious PRs on self-hosted runner, supply chain | Runner executes only trusted pushes/PRs (no fork PRs), pinned actions, dependency and image scanning, non-root images |

## Project Structure

### Documentation (this feature)

```text
specs/004-ecommerce-platform-mvp/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   ├── openapi/{identity,catalog,cart,order,payment,notification}.yaml
│   ├── asyncapi/events.yaml
│   ├── gateway-routes.md
│   └── pact-matrix.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
build-logic/                         # convention plugins: kotlin-domain, kotlin-application,
                                     #   kotlin-service (Spring Boot + adapters), pact, pitest, docker
gradle/libs.versions.toml            # single dependency catalogue
settings.gradle.kts
build.gradle.kts

services/
├── gateway/                         # Spring Cloud Gateway (Kotlin): routes, JWT, rate limit, correlation
│   └── src/main/kotlin, src/test, src/integrationTest, src/contractTest   # built with platform/docker/Dockerfile
├── identity/
│   ├── domain/                      # pure Kotlin: Account, Credential, Address, value objects, events
│   ├── application/                 # use cases + ports (RegisterAccount, SignIn, ResetPassword…)
│   └── infrastructure/              # Spring Boot app, web adapters, R2DBC, Kafka outbox,
│                                    #   src/{test,integrationTest,contractTest,acceptanceTest}
├── catalog/      {domain, application, infrastructure}
├── cart/         {domain, application, infrastructure}
├── order/        {domain, application, infrastructure}
├── payment/      {domain, application, infrastructure}   # simulated provider adapter
└── notification/ {domain, application, infrastructure}   # email (Mailpit locally) + SMS simulator

platform/
├── compose/                         # docker-compose.yml, profiles core|observability|ci, seed data
├── docker/                          # the single parameterised multi-stage Dockerfile (build arg SERVICE_MODULE) shared by all services
├── observability/                   # otel-collector, loki, tempo, prometheus, grafana provisioning
└── ci-runner/                       # actions-runner container, registry:3, pact-broker compose

contracts/                           # source of truth copied from specs/004/contracts at implementation
├── openapi/*.yaml
└── asyncapi/events.yaml

acceptance/                          # Cucumber features (ubiquitous language) + step definitions
                                     #   running against the gateway of a Compose `core` stack
frontend/README.md                   # reserved location; out of scope
.github/workflows/                   # per-service path-filtered pipelines + platform pipeline
```

**Structure Decision**: Multi-service monorepo. Each bounded context is three Gradle modules so the
dependency rule (domain ← application ← infrastructure) is enforced by module boundaries, with
Konsist as a second guard. The gateway is a single module because it has no domain. Platform
concerns (compose, observability, CI runner) live under `platform/` so services stay free of
operational files except their own Dockerfile. Contracts live at the repository root once
implementation starts, versioned alongside the code they bind.

## Requirement Traceability

| Requirements | Design artifact |
|--------------|-----------------|
| FR-001–003 (catalogue, operators, withdrawal) | contracts/openapi/catalog.yaml; data-model.md §Catalog |
| FR-004–007 (identity, deny-by-default, throttling, anonymisation) | contracts/openapi/identity.yaml; gateway-routes.md; data-model.md §Identity; threat model |
| FR-008–010 (cart, merge, price change, cart revision) | contracts/openapi/cart.yaml (`revision`); data-model.md §Cart; events CartMerged |
| FR-011–017 (orders, stale-revision refusal, synchronous reservation, idempotency, two-status lifecycle, cancellation reasons, operator transitions) | contracts/openapi/order.yaml (`cartRevision`, 409 price-changed, `orderStatus`/`paymentStatus`), payment.yaml; data-model.md §Order/§Payment state tables; events order.*, payment.*, catalog.stock.* |
| FR-018–020 (notifications, retries, preferences) | contracts/openapi/notification.yaml, identity.yaml (preferences); events consumed by notification; data-model.md §Notification |
| FR-021–026 (boundaries, events, gateway, discovery, correlation, health/metrics) | research.md §1–3, §7; gateway-routes.md; asyncapi/events.yaml; platform/observability |
| FR-027 (one-command local run) | quickstart.md; platform/compose |
| FR-028 (independent CI per service, contract tests) | research.md §4–6; pact-matrix.md; .github/workflows |
| FR-029 (API-first, behaviour-level acceptance) | acceptance/ features derived from spec acceptance scenarios |

## Design Decisions Resolving Spec Gaps

Decisions taken during Phase 1 where the spec was silent or ambiguous; each is reflected in the
artifacts named and should be mirrored back into the spec by `/speckit-clarify` if desired.

| Gap | Decision | Where |
|-----|----------|-------|
| Stock reservation timing (was a Phase 1 decision) | Now a spec requirement (FR-012, Clarifications 2026-10-02): synchronous reserve request from order to catalogue at checkout, before payment; commit on approval; release on failure, cancellation or expiry. Covered by a Pact contract. | spec.md FR-012, pact-matrix.md, data-model.md §3.2/§3.4 |
| Order lifecycle (US5 vs FR-015 disagreed) | Resolved in the spec (Clarifications 2026-10-02): two statuses. `orderStatus` placed → preparing → shipped → delivered, cancelled from placed or preparing; `paymentStatus` pending → approved or failed; preparing requires approved payment; failed or 30-minute-expired payment cancels the order with a recorded reason. | spec.md FR-015/FR-017, data-model.md §3.4, order.yaml, events.yaml |
| Price change between cart view and checkout | Resolved in the spec (Clarifications 2026-10-02): the cart exposes a revision; checkout carries it and is refused with the changed lines if any price moved since; resubmission with the current revision freezes current prices. | spec.md FR-010/FR-011, cart.yaml, order.yaml |
| FR-007 anonymisation versus open orders needing a delivery address | Address snapshots on open orders are kept until the order reaches a terminal state, then scrubbed; account PII is anonymised immediately. | data-model.md §3.1/§3.4, §5 |
| Verification and reset links need tokens the notification service does not hold | Tokens travel inside `AccountRegistered` and `PasswordResetRequested` payloads, marked sensitive, never logged, and the topic is restricted to the notification consumer. | events.yaml, threat model |
| Phone number verification for SMS opt-in had no flow | Identity exposes start/confirm phone-verification endpoints; SMS is sent only to verified numbers. | identity.yaml |
| Decline categories and simulator rules undefined | One shared enum (`insufficient_funds`, `card_expired`, `card_rejected`, `suspected_fraud`, `invalid_payment_method`) and a deterministic rule document readable by operators. | payment.yaml, order.yaml, events.yaml, research §11 |
| Account enumeration on register/reset | Both return a generic 202 regardless of whether the email exists. | identity.yaml, threat model |
| Cross-shopper order access | Returns 404 rather than 403 so order ids cannot be probed. | order.yaml |

| Payment status on expiry | A payment still `pending` after 30 minutes is set to `failed` when the order is cancelled with `PAYMENT_EXPIRED`, so no cancelled order keeps a `pending` payment. | data-model.md §3.4, events.yaml |
| Events on payment decline | A decline publishes `OrderPaymentFailed` only (not also `OrderCancelled`), so the catalogue receives exactly one release signal; `OrderCancelled` is reserved for shopper, operator and expiry cancellations. | data-model.md §4.1, events.yaml |
| Catalogue no longer subscribes to `OrderPlaced` | Reservation is synchronous at checkout, so the catalogue consumes only `OrderPaid` (commit), `OrderPaymentFailed` and `OrderCancelled` (release). | events.yaml, pact-matrix.md |
| Refused checkouts and idempotency | A checkout refused for a stale cart revision or insufficient stock stores no idempotency record, so the shopper can resubmit under the same key after fixing the cart. | data-model.md §3.4, order.yaml |

Suggested spec follow-ups: add the phone-verification step to US3 (the lifecycle and stock
reservation items were resolved by `/speckit-clarify` on 2026-10-02).

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 19 Gradle modules (3 per service) | Enforces the hexagonal dependency rule by construction and lets domain modules stay framework-free (Principle II) | Single module per service with Konsist only: rules become advisory and a framework import in the domain compiles until a test runs |
| Flyway migrations use blocking JDBC at start-up | No mature non-blocking migration tool; runs once before serving traffic | Hand-rolled R2DBC migrations: more code, weaker tooling; skipping migrations: unrepeatable schemas |
| Extra platform containers (Pact Broker, private registry, OTel collector, Loki, Tempo, Prometheus, Grafana, Mailpit) | Required by Principles V (contract verification) and VI (observability), and by the requester's CI/registry decisions | File-based pacts and plain log files: no cross-service verification history, no correlation search |
| Gateway implemented as code rather than a config-only proxy | Security rules (JWT, rate limits, correlation sanitising) must be unit-, contract- and mutation-tested like any other Kotlin code (Principles III, V) | Traefik/Kong: faster to stand up, but security logic lives in config outside the test contract |
| Private registry on the runner host instead of a hosted registry | Requester decision; keeps images off public registries for now | Hosted registry (GHCR) is simpler for pulls elsewhere; revisit when deploying beyond the runner host |

## Phase Status

- [x] Phase 0 research complete → [research.md](research.md)
- [x] Phase 1 design complete → [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)
- [x] Constitution Check re-evaluated after design (table above)
- [x] Re-planned on 2026-10-02 after `/speckit-clarify` (lifecycle, stock reservation, price change); data model, contracts and quickstart regenerated to match
- [ ] Phase 2 tasks → `/speckit-tasks` (recommend grouping tasks per service, with build-logic and platform first)
