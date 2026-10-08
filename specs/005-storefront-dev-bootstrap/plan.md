# Implementation Plan: Web Storefront and Local Development Bootstrap

**Branch**: `005-storefront-dev-bootstrap` (spec directory; work happens on `main` or short-lived branches per CONTRIBUTING.md) | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/005-storefront-dev-bootstrap/spec.md`

## Summary

Add the browser storefront that feature 004 deferred and a one-command bootstrap for developer
machines. The storefront is a TypeScript + React single-page application built with Vite, served by
a small static container behind the existing gateway at the same origin as the API; the gateway
gains two Kotlin capabilities: an encrypted, HttpOnly *browser session* cookie so no token ever
reaches page scripts (30-minute idle expiry, silent renewal, ends with the browser) and an
anonymous, throttled telemetry route forwarding OpenTelemetry data from the browser to the existing
collector. The bootstrap is `scripts/dev-env.sh` with `init`, `check`, `status`, `update`, `reset`
and `down`, detect-first with opt-in installs, dry run, non-interactive mode and an explicit
runner-host mode, tested offline with stubbed tools like the feature 003 scripts.

## Technical Context

**Language/Version**: Frontend: TypeScript (strict) on Node 24 LTS, React 19, Vite. Gateway
additions: Kotlin 2.3.x / JDK 25 as pinned in `gradle/libs.versions.toml`. Scripts: Bash
(`#!/usr/bin/env bash`, `set -euo pipefail`), shellcheck-clean.

**Primary Dependencies**: React Router, TanStack Query, `openapi-typescript` + `openapi-fetch`
(typed client generated from the OpenAPI contracts), React Hook Form, CSS Modules, OpenTelemetry JS
web SDK (trace web, fetch, document-load, user-interaction instrumentations, logs SDK). Gateway:
Spring Cloud Gateway (existing), JDK AES-GCM for the sealed cookie. Static server:
`nginxinc/nginx-unprivileged` (digest-pinned). Full table in [research.md §5](research.md).

**Storage**: None new. Browser: two HttpOnly cookies set by the gateway (`__Host-session` and
`__Host-cart` over HTTPS, `session` and `cart` over plain HTTP, research §2), `sessionStorage` for
a random telemetry session id and checkout step ids only; no `localStorage`.
Gateway stays stateless (sealed cookies keyed by `BROWSER_SESSION_KEY` shared by replicas).

**Testing**: Vitest + fast-check (property tests by default for `domain/`, `session/`,
`telemetry/`), Testing Library + MSW for components, Pact JS consumer contracts per provider,
Cucumber.js + Playwright acceptance (`frontend/acceptance/`, `@axe-core/playwright`), Stryker
≥ 80 % on the pure modules. Gateway: Kotest property tests for the cookie sealing and idle rules,
integration tests with WireMock identity, Pact provider verification of the storefront's gateway
pact, Pitest ≥ 80 % (existing floor). Scripts: `scripts/tests/test_dev_env_*.sh` with stubs, no
network, no engine; shellcheck in the platform workflow extended to `scripts/`.

**Target Platform**: Browsers: current Chromium, Firefox and WebKit releases, 360–1440 px
viewports. Containers: Linux, non-root, in the existing Compose `core` profile. Bootstrap: macOS
and Linux shells (Windows via WSL only).

**Project Type**: Web application frontend (SPA) + small gateway extension + developer CLI
scripts, inside the existing monorepo.

**Performance Goals**: SC-004: p95 page content within 2 s and p95 action feedback within 1 s on
the local stack, measured from browser telemetry; SC-009: platform cold start stays under 5 min
with the storefront image (storefront build ≈ 1 min, runs in parallel with nothing: images build
one at a time under `COMPOSE_PARALLEL_LIMIT=1`, so the Node build must stay small and cached);
SC-002: `check` under 30 s, idempotent rerun under 10 s.

**Constraints**: Single origin through the gateway (FR-013); CSP without `unsafe-inline`
(research §6); no tokens readable by scripts (FR-014); telemetry without personal data (FR-032);
quiet npm (`--silent`) and silent `verify`; `frontend/package.json` must expose `lint` and `test`
(already wired by `repository-root.gradle.kts` into `verify`); scripts never touch containers that
are not the platform's (FR-028); secrets never printed (FR-022).

**Scale/Scope**: ~14 routes/screens (home, category, search, product, cart, sign-in, register,
verify, reset, checkout, confirmation, orders, order, account; console: orders, order, stock), 7
Pact consumer contracts, ~10 Gherkin features, 2 new gateway filters + 3 routes, 1 Dockerfile,
1 Compose service, 1 workflow, 1 script with 6 subcommands and ~15 checks.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Gate | Pre-research | Post-design |
|---|-----------|------|--------------|-------------|
| I | Kotlin-idiomatic monorepo | Gateway additions in the existing module through convention plugins; no new Gradle module; version catalog untouched for JS (npm lockfile is the frontend's catalog, `checkVersionLiterals` skips `node_modules/`) | PASS | PASS |
| II | Clean/Hexagonal + DDD | Frontend mirrors the layering in TypeScript: `domain/` (pure value objects and rules), `app/` (use cases: session, cart, checkout, telemetry), `api/` (adapters: generated client), `ui/` (React); ESLint import boundaries enforce the dependency rule. Gateway browser-session code is an adapter around a pure `SealedSession` value type | PASS | PASS – [data-model.md](data-model.md) defines the value objects and state machines |
| III | Security by design | Threat model in spec; gateway-held credentials, SameSite + `Sec-Fetch-Site` origin check, strict CSP per route, telemetry allow-list + collector redaction, throttled anonymous routes, secrets generated into `.env` only, opt-in installs with announced commands, dependency scanning (`osv-scanner`, Trivy) in the storefront pipeline | PASS | PASS – contracts mark auth per operation; OWASP mapping below |
| IV | Functional & non-blocking | Gateway filters are reactive (no blocking); frontend `domain/` and `app/` are pure modules with `Result`-style values, effects at the `api/` and `ui/` edges | PASS | PASS |
| V | Layered test contract | Property tests default (fast-check / Kotest), component tests, Pact JS consumer per provider with provider verification already in place, Cucumber.js + Playwright behaviour-level features, Stryker and Pitest ≥ 80 %; scripts tested offline | PASS | PASS – [contracts/pact-matrix.md](contracts/pact-matrix.md) lists every edge |
| VI | Microservice boundaries | No new datastore; storefront talks only to the gateway; new routes are additive and versioned (`/api/v1/telemetry/**`); the sealed-cookie behaviour is opt-in by header so API clients are unaffected | PASS | PASS |
| VII | TypeScript + React frontend | strict TS, functional components, typed client from OpenAPI, Vitest/fast-check/Testing Library/Pact JS/Cucumber.js + Playwright/Stryker, ESLint + Prettier, no `dangerouslySetInnerHTML`, CSP-compatible bundle, no `localStorage` | PASS | PASS |
| VIII | Token-efficient harness | Quiet npm scripts; `frontendCheck` already in `verify`; Stryker step in hooks and `pr-gate` becomes active once `frontend/stryker.config.*` exists; subagents used for Phase 1 artifacts and for implementation per area | PASS | PASS |

No gate failures. Justified deviations are in Complexity Tracking.

### OWASP mapping (Principle III)

| Area | Assets | Main abuse cases | Controls (OWASP Top 10 / API Top 10) |
|------|--------|------------------|---------------------------------------|
| Browser session (gateway) | access/refresh tokens, roles | token theft via XSS, CSRF, session fixation, replay after sign-out | HttpOnly sealed cookie (A07), `SameSite=Strict` + `Sec-Fetch-Site`/`Origin` check (A01), idle expiry 30 min + rotation via identity refresh, cookie deleted on sign-out and on unseal failure, AES-GCM with per-cookie nonce (A02), cookie never logged |
| Storefront bundle | page integrity | XSS through product content/search, clickjacking, open redirect on verify/reset links | React escaping + no `dangerouslySetInnerHTML` (A03), CSP without inline (A05), `frame-ancestors 'none'`, redirect targets restricted to same-origin route list (A01) |
| Telemetry route | observability data, collector availability | PII exfiltration into logs, flooding | attribute allow-list + collector redaction (A09), anonymous `browse` tier throttling, 256 KiB body cap, cookies/authorization stripped (API4) |
| Anonymous cart | cart contents | cart token theft/guessing | HttpOnly `__Host-cart`, server-side opaque tokens (API1) |
| Console | operator actions | privilege escalation from UI | authorisation decided by services (API5); UI only hides; acceptance `authorisation-sweep` extended |
| Bootstrap | local secrets, developer machine | secret in output or VCS, silent installs, destroying unrelated containers | generated into git-ignored `.env`, never echoed (A02), `--install` opt-in + announced + confirmed `sudo`, `down`/`reset` scoped to the Compose project (A05) |

## Project Structure

### Documentation (this feature)

```text
specs/005-storefront-dev-bootstrap/
├── plan.md              # This file
├── research.md          # Phase 0 decisions
├── data-model.md        # Phase 1: browser-side models, sealed session, environment checks
├── quickstart.md        # Phase 1: validation walk-through
├── contracts/
│   ├── openapi/
│   │   ├── gateway-browser-session.yaml   # X-Browser-Session behaviour, cookies, telemetry route
│   │   └── telemetry.yaml                  # /api/v1/telemetry/** (OTLP/HTTP pass-through contract)
│   ├── gateway-routes.md                   # additions to the 004 route table (catch-all, telemetry, CSP)
│   ├── pact-matrix.md                      # storefront consumer → provider edges
│   ├── dev-env-cli.md                      # scripts/dev-env.sh command contract (subcommands, flags, exit codes, output)
│   └── storefront-routes.md                # URL routes of the SPA and the API operations each uses
├── checklists/requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
frontend/                                  # replaces the reserved README
├── package.json                           # scripts: lint, test, generate, build, acceptance, mutate
├── package-lock.json, .nvmrc (24), tsconfig.json, vite.config.ts, eslint.config.js, .prettierrc
├── stryker.config.json                    # activates the harness' Stryker step
├── index.html                             # no inline script/style
├── public/
├── src/
│   ├── domain/            # pure TS: Money, Email, Password, Quantity, Address, CartRevision, OrderStatus, PaymentStatus, route templates
│   ├── app/               # use cases: session (sign-in/out, renewal awareness), cart, checkout (draft + idempotency key), orders, console, telemetry policy
│   ├── api/               # generated/ (openapi-typescript output, git-ignored), client.ts (openapi-fetch, X-Browser-Session, X-Correlation-Id), problem.ts
│   ├── telemetry/         # OpenTelemetry web SDK setup, attribute allow-list, route-template mapping, exporter to /api/v1/telemetry
│   ├── ui/                # React: routes/, components/, pages/, styles (CSS Modules, tokens)
│   └── main.tsx
├── tests/                 # Vitest: domain/app property tests, component tests (Testing Library + MSW)
├── pact/                  # Pact JS consumer tests, one file per provider → build/pacts at the repo root
└── acceptance/            # Cucumber.js + Playwright: features/*.feature, steps/, support/ (runs when STOREFRONT_URL is set)

services/gateway/src/main/kotlin/com/ecommerce/gateway/
├── browser/               # BrowserSessionFilter, SealedSession (AES-GCM), CartCookieFilter, OriginCheck, BrowserSessionProperties
├── web/EdgeHeaders.kt     # per-route CSP (API strict vs storefront policy)
└── resources/application.yml   # routes: storefront catch-all, telemetry-traces, telemetry-logs; STOREFRONT_URL, OTEL_COLLECTOR_URL, BROWSER_SESSION_KEY
services/gateway/src/{test,integrationTest,contractTest}/...   # sealing/idle property tests, WireMock identity refresh, pact provider states for the storefront

platform/
├── docker/Dockerfile.storefront, docker/storefront/nginx.conf
├── compose/docker-compose.yml           # + storefront service, gateway STOREFRONT_URL/BROWSER_SESSION_KEY, OTEL route target
├── compose/.env.example                 # + BROWSER_SESSION_KEY=
├── observability/otel-collector.yaml    # + attributes/redaction processors for service storefront
└── observability/grafana/dashboards/storefront-rum.json

scripts/
├── dev-env.sh                           # init | check | status | update | reset | down (+ --install --start --yes --dry-run --runner-host)
├── lib/common.sh                        # reused; + os/engine detection helpers in lib/dev-env/*.sh
└── tests/test_dev_env_*.sh, tests/stubs/{docker,podman,java,node,npm,brew,gitleaks,...}

.github/workflows/storefront.yml         # path-filtered storefront pipeline (lint, test, Stryker, pact publish, osv-scanner, image, Trivy, SBOM, image-health, publish)
.github/scripts/image-health.sh          # + storefront case (/healthz on 8080)
docs/{running-locally.md, build.md, gateway.md, ci-cd.md, service-conventions.md, architecture.md, storefront.md}, CONTRIBUTING.md, README.md
acceptance/src/test/resources/features/platform-observability.feature   # + storefront correlation scenario
```

**Structure Decision**: a single npm package at `frontend/` (the root build and hooks already expect
exactly that path and its `lint`/`test` scripts); gateway changes stay inside the existing module;
the bootstrap joins `scripts/` next to the feature 003 scripts and reuses their library and test
harness; images, Compose and workflows follow the service conventions so nothing new is needed in
`build-logic/`.

## Requirement Traceability

| Spec item | Design element |
|-----------|----------------|
| FR-001–FR-003 (browse, product, URL state) | `ui/pages/{Home,Category,Search,Product}`, React Router URL params, catalog client; [storefront-routes.md](contracts/storefront-routes.md) |
| FR-004 (cart, merge, persistence) | `app/cart`, `__Host-cart` cookie (research §3), `mergeCart` on sign-in |
| FR-005 (identity flows, no enumeration) | `app/session`, identity client, uniform messages mirrored from identity's 202 responses |
| FR-006–FR-009 (checkout, price change, idempotency, statuses) | `app/checkout` draft with one idempotency key per draft, `CartRevision` acknowledgement, TanStack Query polling while `pending` |
| FR-010 (own orders, cancel, account) | `ui/pages/{Orders,Order,Account}`, order/identity clients |
| FR-011–FR-012 (minimal console, role gate) | `ui/console/*` under `/console`, `roles` from the session summary, server 403 surfaced; `authorisation-sweep.feature` extension |
| FR-013, FR-019 (single origin, images) | gateway catch-all route, `storefront` container, `Dockerfile.storefront` (research §1, §7) |
| FR-014 (credentials unreadable, CSRF, 30-min idle) | gateway `browser/` sealed cookie + origin check (research §2); [gateway-browser-session.yaml](contracts/openapi/gateway-browser-session.yaml) |
| FR-015 (text rendering) | React escaping, lint rule against `dangerouslySetInnerHTML`, CSP (research §6) |
| FR-016 (states, throttling) | `api/problem.ts` → UI states; `Retry-After` countdown; TanStack Query retry disabled on 429 |
| FR-017 (a11y, responsive) | jsx-a11y lint, axe in acceptance, CSS tokens with 360 px baseline |
| FR-018 (quality gate, acceptance, contracts) | `frontendCheck` in `verify`, Pact JS + provider verification, Cucumber.js features |
| FR-020–FR-030, FR-033 (bootstrap) | `scripts/dev-env.sh` (research §8–§9); [dev-env-cli.md](contracts/dev-env-cli.md); offline tests |
| FR-031–FR-032 (telemetry, privacy) | `telemetry/` module, gateway telemetry routes, collector processors (research §4); [telemetry.yaml](contracts/openapi/telemetry.yaml) |
| SC-001, SC-002, SC-010 | `init --start` end-to-end timing in quickstart §2; `check` timing; idempotency tests |
| SC-003, SC-005 | acceptance features `@us1`–`@us6` driven through the storefront |
| SC-004, SC-011 | Grafana `storefront-rum` dashboard over browser spans; `platform-observability.feature` correlation scenario |
| SC-006 | axe audit in acceptance, keyboard-only scenario |
| SC-007 | pact-matrix rows verified by provider pipelines |
| SC-008 | gitleaks run over a recorded `init` transcript in `test_dev_env_secrets.sh` |
| SC-009 | cold-start measurement repeated in quickstart §2 after adding the image |

## Design Decisions Resolving Spec Gaps

- **Session summary for the UI**: because tokens never reach the page, the browser needs a
  tokenless way to know who is signed in: the sealed-cookie sign-in response returns
  `{expiresAt, roles}` and `GET /api/v1/identity/accounts/me` (through the cookie) is the source of
  truth on page load; a 401 means "anonymous".
- **Session expiry during checkout (US2 scenario 10)**: the checkout draft lives in component
  state plus `sessionStorage` (address id, payment method id, acknowledged revision, idempotency
  key; no personal data beyond an address *id*), so re-authentication returns to the same step.
- **Payment pending (FR-009)**: the storefront polls `GET /api/v1/orders/{id}` every 5 s while
  payment status is `pending` and shows the countdown from a new additive `paymentExpiresAt`
  field of the order contract (present while the payment is pending; the window is the order
  service's configured payment window, so the client never recomputes it); polling stops on a
  final status or after the deadline passes. This is the one provider change this feature asks
  of feature 004's services, recorded in [contracts/gateway-routes.md](contracts/gateway-routes.md)
  and the pact matrix.
- **Order number**: the order `id` is the order number; the storefront shows it shortened (first
  8 characters) with the full value available to copy. No new field.
- **Shopper cancellation**: only while `placed` (order contract); the console offers cancellation
  while `placed` or `preparing`. The spec's US4 scenario 2 was aligned on 2026-10-04.
- **Idempotency key**: one key per distinct confirmation body, reused for retries, replaced after
  a price acknowledgement (the order contract answers 422 to a reused key with a new body).
- **Session summary `expiresAt`**: the end of the idle window (`lastSeenAt` + 30 min) as of the
  response; the UI uses it only to warn before expiry, the gateway decides.
- **Missing `Sec-Fetch-Site`**: the origin check fails closed (403) for a non-GET request with a
  session cookie that carries neither `Sec-Fetch-Site` nor a matching `Origin`.
- **Operator order listing (console)**: feature 004's `listOwnOrders` is role `shopper` only, so the console's
  "all orders" view needs a second additive provider change: `GET /api/v1/orders` accepts the operator role and
  then returns every shopper's orders, with an optional `orderStatus` query parameter (both OpenAPI copies, order
  authorisation in the application layer, provider state O9). Found by the order provider verification on
  2026-10-07; delivered with US6 (T088).
- **Pending payment attempt id**: the storefront must not assert `paymentAttemptId` is null while a payment is
  pending (the id exists once the provider was reached); the consumer pact uses a nullable matcher.
- **Payment-method options for shoppers**: payment's `getSimulatorRules` is operator-only, so the
  storefront ships a build-time list of the seeded simulator methods (approving, declining,
  pending) in `frontend/src/domain/paymentMethods.ts`, labelled as local-development methods, for
  every caller; the storefront does not read the rules document (the console offers only order
  fulfilment and stock adjustment, FR-011; the planned read was withdrawn by convergence task
  T109). A real provider would replace this list with a provider SDK.
- **Categories by id**: feature 004 categories have no slug, so category pages resolve by id;
  the URL still carries page and sort state (FR-003).
- **Cookie edge cases** (decided in [contracts/openapi/gateway-browser-session.yaml](contracts/openapi/gateway-browser-session.yaml)):
  the session cookie is ignored on register, verify and password-reset operations and replaced on
  sign-in; sign-out always answers 204 and deletes the cookie; an upstream 401 deletes it; a
  request carrying both cookie names is 401 with both deleted; cart cookie handling applies only
  with `X-Browser-Session: cookie`.
- **Console order list**: order's `GET /api/v1/orders` already serves operators all orders
  (feature 004 FR-017); the console filters by status client-side within a page and server-side
  where the contract has a parameter.
- **Product images**: external URLs registered by operators; the storefront shows a neutral
  placeholder when an image fails to load (edge case) and CSP allows `img-src https: data:`.
- **Verification and reset links**: notification's `PUBLIC_BASE_URL` already follows
  `GATEWAY_PORT`; the storefront owns `/verify-email?token=…` and `/reset-password?token=…` routes
  and submits the token to identity; the links in emails therefore point at the storefront, which
  requires notification's templates to use these paths (small change recorded as a task).
- **Runner-host mode**: `--runner-host` starts `platform/compose` with the `ci` profile and
  `platform/ci-runner/docker-compose.yml` registry service, runs `image-health`-style checks, and
  prints the "Register the runner" section link; it never reads `ACCESS_TOKEN`.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Browser-session logic in the gateway (a second responsibility beyond routing) | FR-014 forbids tokens readable by page scripts; the gateway is the only shared, stateless, already-tested edge | Access token in memory: readable by scripts; Node BFF: a second backend runtime needing its own contracts and mutation tests; identity setting cookies: leaks browser concerns into a domain service |
| A second Dockerfile (`Dockerfile.storefront`) beside the parameterised JVM one | Node build + nginx runtime share nothing with the JDK/JRE stages | Forcing the storefront through the JVM Dockerfile would install Node into the build stage and ship nginx from a JRE image |
| `sessionStorage` use (checkout draft ids, telemetry session id) | Survives re-authentication redirects and page reloads inside one tab; holds no credential or personal data | Pure in-memory state loses the checkout step on the sign-in round trip (US2 scenario 10) |
| Client-side status filtering in the console | Order contract has limited server filters; adding query parameters is an additive change deferred to keep this feature's backend surface small | Changing order's API now widens scope into feature 004's provider |

## Phase Status

- [x] Phase 0 research complete → [research.md](research.md)
- [x] Phase 1 design complete → [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)
- [x] Constitution Check re-evaluated after design (table above)
- [x] Phase 2 tasks → [tasks.md](tasks.md) (106 tasks in 10 phases; gateway browser session, storefront core and image in the foundational phase, bootstrap scripts in parallel, telemetry and CI/docs last)
