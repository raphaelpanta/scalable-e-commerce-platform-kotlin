# Tasks: Web Storefront and Local Development Bootstrap

**Input**: Design documents from `/specs/005-storefront-dev-bootstrap/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/, quickstart.md. Feature 004 (platform, contracts, seed data, Compose stack, CI pipelines) is implemented and is the provider of everything the storefront consumes; `repository-root.gradle.kts` already registers `frontendLint`/`frontendTest`/`frontendCheck` into `verify` as soon as `frontend/package.json` exists, and the hooks already run Stryker when `frontend/stryker.config.*` exists.
**Tests**: Included and mandatory. Constitution Principle V and VII require property-based unit tests (fast-check, Kotest), component tests (Testing Library), Pact JS consumer contracts with provider verification, behaviour-level Cucumber acceptance (Cucumber.js + Playwright for the storefront, Cucumber JVM for the platform scenario), Stryker and Pitest ≥ 80 %, and offline tests for the scripts. Test tasks come before the implementation they cover (red-green).
**Organization**: Tasks are grouped by user story (US1–US6 from spec.md) so each story is an independently testable increment; telemetry (FR-031/FR-032) is a cross-cutting phase because no single story owns it.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1–US6)
- Include exact file paths in descriptions

## Path Conventions

- Storefront: npm package at `frontend/` (Node 24, `frontend/.nvmrc`); layers `frontend/src/domain` (pure), `frontend/src/app` (use cases), `frontend/src/api` (generated client + adapters), `frontend/src/telemetry`, `frontend/src/ui` (React: `routes/`, `pages/`, `components/`, `styles/`); tests in `frontend/tests/{domain,app,api,ui}`, Pact consumers in `frontend/pact/`, acceptance in `frontend/acceptance/{features,steps,support}`.
- Gateway: `services/gateway/src/main/kotlin/com/ecommerce/gateway/{browser,web,config}`, tests in `services/gateway/src/{test,integrationTest,contractTest}/kotlin/com/ecommerce/gateway/...`, config in `services/gateway/src/main/resources/application.yml`.
- Order service (one additive change): `services/order/{domain,application,infrastructure}`.
- Platform: `platform/docker/Dockerfile.storefront`, `platform/docker/storefront/nginx.conf`, `platform/compose/docker-compose.yml`, `platform/compose/.env.example`, `platform/observability/`.
- Scripts: `scripts/dev-env.sh`, `scripts/lib/dev-env/*.sh`, tests `scripts/tests/test_dev_env_*.sh`, stubs `scripts/tests/stubs/`.
- Contracts source of truth copies: `contracts/openapi/` (root) mirrors `specs/005-storefront-dev-bootstrap/contracts/openapi/`.
- Quiet output everywhere: npm scripts run with `--silent`, Gradle with `-q`; `verify` must stay silent on success.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the storefront package so the existing root build picks it up, wire the generated typed client, and publish the new contracts where the repository keeps them.

- [X] T001 Replace the reserved `frontend/README.md` with the real package: create `frontend/package.json` (name `storefront`, `private`, `engines.node >=24 <25`, `engine-strict` in `frontend/.npmrc`, scripts `lint`, `test`, `generate`, `build`, `dev`, `acceptance`, `mutate`, `pact`; every script `--silent`-friendly and non-interactive with `CI=1`), `frontend/.nvmrc` (`24`), `frontend/package-lock.json` (committed), `frontend/.gitignore` (`dist/`, `node_modules/`, `src/api/generated/`, `build/`), and an updated `frontend/README.md` describing layers, scripts and the quality gate
- [X] T002 [P] Configure TypeScript and Vite: `frontend/tsconfig.json` (`strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noImplicitOverride`, `verbatimModuleSyntax`, path aliases `@domain/*`, `@app/*`, `@api/*`, `@telemetry/*`, `@ui/*`), `frontend/vite.config.ts` (React plugin, hashed asset file names under `dist/assets/`, `base: '/'`, dev server proxy of `/api` to `http://localhost:${GATEWAY_PORT:-8080}`), `frontend/index.html` with no inline script or style
- [X] T003 [P] Configure lint and format: `frontend/eslint.config.js` (typescript-eslint strict-type-checked, react, react-hooks, jsx-a11y strict, import boundaries: `src/domain` imports nothing from `app|api|ui|telemetry` or browser globals, `src/app` imports `domain` only, `no-restricted-syntax` forbidding `dangerouslySetInnerHTML`, `localStorage`, `eval`, `innerHTML`), `frontend/.prettierrc`, `frontend/.prettierignore`; `npm run lint` = `prettier --check . && eslint --max-warnings 0 . && tsc --noEmit && npm run generate -- --check`
- [X] T004 [P] Configure unit testing: `frontend/vitest.config.ts` (jsdom, coverage off, `setupFiles: tests/setup.ts`), `frontend/tests/setup.ts` (Testing Library cleanup, MSW server `frontend/tests/msw/server.ts` with `onUnhandledRequest: 'error'`), fast-check with a fixed seed from `FC_SEED`, reporters quiet (`--reporter=dot`, failures only)
- [X] T005 [P] Configure mutation testing: `frontend/stryker.config.json` (`vitest` runner, `mutate: ["src/domain/**", "src/app/**", "src/telemetry/**"]`, `thresholds.break: 80`, `incremental: true`, `incrementalFile: build/stryker-incremental.json`, reporters `progress-append-only` off / `clear-text` on failure only) and `npm run mutate`
- [X] T006 [P] Create the typed-client generation: `frontend/scripts/generate-api.mjs` running `openapi-typescript` over `specs/004-ecommerce-platform-mvp/contracts/openapi/{identity,catalog,cart,order,payment}.yaml` and `specs/005-storefront-dev-bootstrap/contracts/openapi/{gateway-browser-session,telemetry}.yaml` into `frontend/src/api/generated/*.d.ts`; `--check` mode fails when the generated output differs from a fresh run (freshness guard used by `lint`); document in `frontend/README.md` that hand-written response types are forbidden
- [X] T007 [P] Copy the new contracts to the repository's source-of-truth location: `contracts/openapi/gateway-browser-session.yaml`, `contracts/openapi/telemetry.yaml` (byte-identical to `specs/005-storefront-dev-bootstrap/contracts/openapi/`), and add both to the table in `contracts/README.md`
- [X] T008 [P] Configure Pact JS consumer tests: `frontend/pact/pact.config.ts` (pact dir = repository root `build/pacts`, consumer `storefront`, spec v4, log level warn), `npm run pact` running `frontend/pact/*.pact.test.ts` with Vitest; verify `./gradlew -q contractTest` picks up `build/pacts/storefront-*.json` for provider verification (document the ordering in `docs/build.md` task T147)
- [X] T009 [P] Configure acceptance tooling: `frontend/acceptance/cucumber.mjs` (Cucumber.js, `features/**/*.feature`, steps in `steps/`, world in `support/world.ts`, `--format summary`, tags from `CUCUMBER_TAGS`), Playwright Chromium with `STOREFRONT_URL` required (`npm run acceptance` exits 0 with a skip message when unset, mirroring `acceptance/build.gradle.kts`), `@axe-core/playwright` helper in `frontend/acceptance/support/a11y.ts`
- [X] T010 [P] Create the CSS foundation: `frontend/src/ui/styles/tokens.css` (colour, spacing, type scale, focus ring, 360 px baseline, `prefers-reduced-motion`), `frontend/src/ui/styles/global.css` (reset, visually-hidden utility), CSS Modules typing `frontend/src/css-modules.d.ts`; no inline styles anywhere (CSP)
- [X] T011 Confirm the root build integration: run `./gradlew -q verify` after T001–T010 and fix until `frontendLint` and `frontendTest` run silently (empty test suite allowed at this point); record the `-PnpmExecutable` note in `docs/build.md`
- [ ] T012 [P] Add `BROWSER_SESSION_KEY=` with its comment (32 random bytes, Base64; generated by `scripts/dev-env.sh`; `openssl rand -base64 32`) to `platform/compose/.env.example`, and document `BROWSER_SESSION_KEY`, `STOREFRONT_URL`, `OTEL_COLLECTOR_URL` in `docs/service-conventions.md` section 2

**Checkpoint**: `frontend/` exists with silent `lint`/`test`, generated types, Pact/acceptance/Stryker scaffolding; `verify` passes.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The gateway capabilities every signed-in story needs (browser session and cart cookies, storefront route and CSP), the storefront's pure core and shell, and the container that serves it. No story can be demonstrated end to end before this phase.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Gateway: browser session and cart cookies (contracts/openapi/gateway-browser-session.yaml, contracts/gateway-routes.md, data-model.md §4)

- [X] T013 [P] Property tests for sealing in `services/gateway/src/test/kotlin/com/ecommerce/gateway/browser/SealedSessionSpec.kt`: round trip for arbitrary payloads (`accessToken`, `refreshToken`, `accountId`, `roles`, `lastSeenAt`, `issuedAt`), unique 12-byte nonce per sealing, wire format `<keyId>.<Base64url(nonce || ciphertext || tag)>` without padding, unseal failure on a flipped byte, unknown key id, missing key id, or wrong associated data, fail-closed when the cookie would exceed 4 KiB, key rotation (`k1` and `k2` both unseal, unknown `k3` fails)
- [X] T014 [P] Property tests for the session rules in `services/gateway/src/test/kotlin/com/ecommerce/gateway/browser/BrowserSessionRulesSpec.kt`: idle expiry exactly at `lastSeenAt + 30 min` (fixed clock), refresh needed iff the access token `exp` is within 60 s, the request decision table of data-model.md §4.3 (rows 1–6, including `Sec-Fetch-Site` `same-origin`/`none` allowed, `cross-site`/`same-site`/absent refused for non-GET, `Origin` mismatch refused, GET/HEAD/OPTIONS never origin-checked), cookie name selection by scheme (`__Host-session` + `Secure` for HTTPS or `X-Forwarded-Proto: https`, `session` otherwise), both names present → 401 with both deleted
- [X] T015 Implement the pure model in `services/gateway/src/main/kotlin/com/ecommerce/gateway/browser/SealedSession.kt` (data class payload, `SessionSealer` interface, `AesGcmSessionSealer` using JDK `javax.crypto` AES-256-GCM with key set `Map<String, SecretKey>` from `BrowserSessionProperties`, `CookieNames` by scheme, `BrowserSessionDecision` sealed result for the decision table) until T013–T014 pass; no logging of values
- [X] T016 Add `BrowserSessionProperties` to `services/gateway/src/main/kotlin/com/ecommerce/gateway/config/GatewayProperties.kt` (`gateway.browser-session.key` from `BROWSER_SESSION_KEY`, `idle-timeout` default 30 min, `refresh-ahead` default 60 s, `cart-cookie-max-age` default 30 days) with a startup check: outside the `dev` and `test` profiles the key is required and must decode to exactly 32 bytes (same failure style as identity's `IDENTITY_SIGNING_KEY`), in `dev`/`test` a per-process random key is generated; unit test in `services/gateway/src/test/kotlin/com/ecommerce/gateway/config/BrowserSessionPropertiesTest.kt`
- [X] T017 Integration tests in `services/gateway/src/integrationTest/kotlin/com/ecommerce/gateway/BrowserSessionIT.kt` (WireMock identity and a WireMock upstream, fixed `BROWSER_SESSION_KEY`, controllable clock): sign-in with `X-Browser-Session: cookie` returns `{expiresAt, roles}` only and `Set-Cookie: session=...; HttpOnly; SameSite=Strict; Path=/` without `Max-Age`; sign-in without the header is unchanged; cookie request injects `Authorization: Bearer` upstream and re-sets the cookie; refresh-ahead calls identity `/sessions/refresh` and rotates; idle > 30 min → 401 problem and deletion cookie; tampered cookie → 401; cookie + `Authorization` → 400 `validation`; `Sec-Fetch-Site: cross-site` POST → 403; sign-out → 204 and deletion even for an unsealable cookie; HTTPS via `X-Forwarded-Proto` sets `__Host-session; Secure` and deletes `session`; register/verify/password-reset ignore an existing cookie; filter errors carry `X-Correlation-Id` and `Cache-Control: no-store`
- [X] T018 Implement `services/gateway/src/main/kotlin/com/ecommerce/gateway/browser/BrowserSessionFilter.kt` (`GlobalFilter`, order -400, before `RouteAccessFilter` -300): decision table, unseal/idle/refresh via a non-blocking `WebClient` to `IDENTITY_URL`, bearer injection, response decoration that rewrites the sign-in/refresh 200 body to `{expiresAt, roles}` (`expiresAt = lastSeenAt + idle-timeout`), sets/deletes/replaces cookies, deletes the cookie on an upstream 401, ignores cookies on `storefront`/`telemetry-*` routes and on `identity-registration` and password-reset paths; wire in `services/gateway/src/main/kotlin/com/ecommerce/gateway/GatewayConfiguration.kt` (or the existing filter configuration class) until T017 passes
- [X] T019 Integration tests in `services/gateway/src/integrationTest/kotlin/com/ecommerce/gateway/CartCookieIT.kt`: `POST /api/v1/cart/lines` with `X-Browser-Session: cookie` and no cart cookie → upstream `X-Cart-Token` becomes `Set-Cookie: cart=...; HttpOnly; SameSite=Lax; Path=/; Max-Age=2592000` and the response header is removed; `GET /api/v1/cart` with the cookie injects `X-Cart-Token` upstream and an explicit client header wins; `POST /api/v1/cart/merge` 200 deletes the cart cookie, 409 keeps it; without the `X-Browser-Session` header nothing changes
- [X] T020 Implement `services/gateway/src/main/kotlin/com/ecommerce/gateway/browser/CartCookieFilter.kt` (order -350, sealed with the same key set, applies only when `X-Browser-Session: cookie` is present) until T019 passes

### Gateway: storefront route, telemetry routes and per-route CSP

- [X] T021 [P] Route tests: extend `services/gateway/src/test/kotlin/com/ecommerce/gateway/RouteTableTest.kt` for the new ids `telemetry-traces`, `telemetry-logs`, `storefront` (predicates, methods, `auth: anonymous`, `tier: browse`, `max-body-size: 256KB` on telemetry, `storefront` ordered after every `/api/**` route, excluded prefixes `/api/`, `/actuator/`, `/.well-known/`), and `services/gateway/src/test/kotlin/com/ecommerce/gateway/web/EdgeHeadersTest.kt` for the storefront CSP `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: https:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'`, `Permissions-Policy` denying camera, microphone, geolocation, payment, and the unchanged API policy on `/api/**`
- [X] T022 [P] Integration test `services/gateway/src/integrationTest/kotlin/com/ecommerce/gateway/StorefrontRouteIT.kt` (WireMock storefront upstream): `GET /` and `GET /products/0b4e6d1c-…` → 200 HTML with the storefront headers, `Cache-Control: no-store` passed through from upstream for `index.html` and `immutable` for `/assets/x.js`; `POST /` and `POST /products/1` → 404 `not-found` problem; `GET /api/v1/unknown` → 404; `GET /actuator/health` → 404; no `Set-Cookie` processing on this route
- [X] T023 Add the three routes and env defaults to `services/gateway/src/main/resources/application.yml` (`STOREFRONT_URL` default `http://localhost:8082`, `OTEL_COLLECTOR_URL` default `http://localhost:4318`, `StripPrefix`/`RewritePath` of `/api/v1/telemetry` → `/v1/{traces,logs}`, `RemoveRequestHeader` `Cookie` and `Authorization` on telemetry, no `Retry` on telemetry), implement per-route CSP in `services/gateway/src/main/kotlin/com/ecommerce/gateway/web/EdgeHeaders.kt` (match the `storefront` route id), and make `RequestSizeFilter` honour a route `max-body-size` below the default, until T021–T022 pass; keep `RouteTableTest` and `contracts/gateway-routes.md` of feature 004 consistent (append the new rows there with a pointer to feature 005)
- [X] T024 Pact provider verification for the storefront pact: `services/gateway/src/contractTest/kotlin/com/ecommerce/gateway/StorefrontGatewayProviderIT.kt` tagged `provider`, replaying `build/pacts/storefront-gateway.json` against the real gateway with WireMock identity, cart, collector and storefront upstreams, fixed clock and fixed key; provider states G1–G19 of `contracts/pact-matrix.md` implemented in `services/gateway/src/contractTest/kotlin/com/ecommerce/gateway/StorefrontProviderStates.kt`; wire the gateway module into the `pact` convention plugin as a provider (`services/gateway/build.gradle.kts`) so `./gradlew -q contractVerify` runs it after the storefront consumer (the consumer pact file is produced by T037/T065/T134; until then the test skips with a clear message when the pact file is absent)

### Storefront core and shell (data-model.md §2–§3.1, contracts/storefront-routes.md)

- [X] T025 [P] Property tests `frontend/tests/domain/valueObjects.test.ts` (fast-check) for `Email` (trimmed, 1–254 chars, one `@`, dotted domain, lower-cased comparison only), `Password` (length ≥ 12 pre-check only, never trimmed, redacted `toString`), `Quantity` (integer 1..99, `Quantity.zero` only as remove command), `Money` (non-negative safe integer minor units, `^[A-Z]{3}$` currency, `format(locale)` only, equality allowed, no arithmetic exported), `Address` (`recipientName` 1–100, `line1` 1–150, `line2` ≤ 150, `city` 1–100, `region` ≤ 100, `postalCode` 1–20, `countryCode` `^[A-Z]{2}$`, `label` ≤ 50, trimmed), `CartRevision` (opaque non-empty, equality only), `CorrelationId` and `IdempotencyKey` (canonical lower-case UUID v4), `Result` helpers (`ok`/`err`)
- [X] T026 [P] Property tests `frontend/tests/domain/status.test.ts` for `OrderStatus`/`PaymentStatus` transitions exactly as data-model.md §2.1 (`allowedNext`, terminal states, `cancelAllowed(role, status)`: shopper only `placed`, operator `placed` or `preparing`; `advance(preparing)` requires payment `approved`) and `frontend/tests/domain/routeTemplate.test.ts` for `RouteTemplate.fromPathname` over the closed list of §2.2 (ids, tokens and query strings never survive; unmatched → `/unknown`)
- [X] T027 Implement `frontend/src/domain/{result,email,password,quantity,money,address,cartRevision,status,routeTemplate,ids}.ts` as pure modules until T025–T026 pass (no imports from other layers or browser globals; the ESLint boundary rule of T003 must pass)
- [X] T028 [P] Tests `frontend/tests/api/client.test.ts` (MSW): every request carries `X-Browser-Session: cookie` and a canonical `X-Correlation-Id` (renewed per action via `correlation.next()`), `credentials: 'same-origin'`, problem+json mapped to a typed `Problem` (`type` slug, `title`, `detail`, `status`, `correlationId`, `errors[]`), 429 mapped to `Throttled { retryAfterSeconds }`, 401 raised as `Unauthorized` for the session layer, network failure mapped to `Unavailable`, no token or cookie ever read; `frontend/tests/api/redirect.test.ts` for the `next` validation rules of `contracts/storefront-routes.md` (rejects `//evil.example`, `/\evil`, `https://…`, `javascript:`, `/%2F%2Fevil`, `/api/...`, `/unknown`, and the auth routes; accepts only route-list paths)
- [X] T029 Implement `frontend/src/api/client.ts` (`openapi-fetch` over the generated `paths` of T006, middleware for headers and problem mapping), `frontend/src/api/problem.ts`, `frontend/src/app/correlation.ts`, `frontend/src/app/navigation/safeNext.ts` until T028 passes
- [X] T030 [P] Tests `frontend/tests/app/session.test.ts`: state machine of data-model.md §3.1 (`anonymous` → `signedIn` on `{expiresAt, roles}`; → `anonymous` on sign-out, any 401, idle; `expiresAt` is a hint only; `roles` gate console links only), page-load probe via `GET /api/v1/identity/accounts/me` (200 → signedIn, 401 → anonymous), query-cache clearing on sign-out/401, "session about to end" warning at `expiresAt − 2 min`
- [X] T031 Implement `frontend/src/app/session/{sessionStore,useSession}.ts` (TanStack Query based, no token storage, no `localStorage`) until T030 passes
- [X] T032 [P] Component tests `frontend/tests/ui/states.test.tsx` for the shared state components (`Loading` with `role="status"`, `Empty` with one next action, `ErrorState` with retry and collapsible correlation id, `Throttled` with a live countdown and disabled retry, `NotFoundPage` with links to `/` and `/search`) and `frontend/tests/ui/layout.test.tsx` (header with cart badge, sign-in/sign-out, console link only when `roles` contains `operator`, skip link, landmark roles, keyboard focus order)
- [X] T033 Implement the shell: `frontend/src/ui/components/{Loading,Empty,ErrorState,Throttled,ConfirmDialog,Money,ProductImage}.tsx` (`ProductImage` renders a neutral placeholder on error), `frontend/src/ui/pages/NotFoundPage.tsx`, `frontend/src/ui/components/Layout.tsx`, `frontend/src/ui/routes/router.tsx` (React Router data router with the route list of `contracts/storefront-routes.md`, protected-route loader applying the redirect rule `replace` to `/sign-in?next=`), `frontend/src/main.tsx` (QueryClient with `retry` disabled for 4xx/429, `refetchOnWindowFocus` off), until T032 passes
- [X] T034 [P] Acceptance support: `frontend/acceptance/support/world.ts` (Playwright page per scenario, base URL `STOREFRONT_URL`, Mailpit client reading `MAILPIT_URL` default `http://localhost:8025`, seeded operator credentials from env `OPERATOR_EMAIL`/`OPERATOR_PASSWORD` with the feature 004 seed defaults, random shopper email per scenario), `frontend/acceptance/support/hooks.ts` (axe audit on every page visited, fails on `critical`/`serious`), shared steps `frontend/acceptance/steps/common.steps.ts` ("I open the storefront", "I see …", "I sign in as …", keyboard-only navigation helpers)

### Storefront image and Compose integration (research §7)

- [X] T035 [P] Create `platform/docker/Dockerfile.storefront` (stage 1 `node:24-alpine` pinned by digest: `npm ci --ignore-scripts --silent`, `npm run build --silent`; stage 2 `nginxinc/nginx-unprivileged:stable-alpine` pinned by digest, copies `dist/` to `/usr/share/nginx/html`, `platform/docker/storefront/nginx.conf`, `EXPOSE 8080`, `HEALTHCHECK` on `/healthz`, non-root), `platform/docker/storefront/nginx.conf` (listen 8080, `try_files $uri /index.html`, `index.html` `Cache-Control: no-store`, `/assets/` `public, max-age=31536000, immutable`, gzip, `server_tokens off`, `/healthz` returns 200 `ok`, 404 for missing paths whose last segment has a file extension), and document both in `platform/docker/README.md`
- [X] T036 Add the `storefront` service to `platform/compose/docker-compose.yml` (profile `core`, build context repository root with `Dockerfile.storefront`, `mem_limit: ${STOREFRONT_MEM_LIMIT:-64m}`, network `internal` only, healthcheck `wget -qO- http://127.0.0.1:8080/healthz`), add `STOREFRONT_URL: http://storefront:8080`, `OTEL_COLLECTOR_URL: http://otel-collector:4318` and `BROWSER_SESSION_KEY: ${BROWSER_SESSION_KEY:?set in .env}` to the gateway service, make the gateway `depends_on` storefront `service_healthy`; update `platform/compose/README.md` (profiles table, 21 containers, memory note) and `.github/scripts/image-health.sh` with a `storefront` case (no database, probe `GET /healthz` on 8080 inside the container with `wget`)
- [X] T037 Pact consumer `frontend/pact/gateway.pact.test.ts` producing `build/pacts/storefront-gateway.json` with interactions G1–G19 of `contracts/pact-matrix.md` (plain HTTP cookie names; G15 with `X-Forwarded-Proto: https`), exercising the real `frontend/src/api/client.ts` and `frontend/src/app/session` against the Pact mock server; run `./gradlew -q contractTest contractVerify` to verify it against the gateway (T024)
- [X] T038 Smoke the stack: `cd platform/compose && docker compose --profile core --profile observability up -d --build` (set `BROWSER_SESSION_KEY` in `.env` with `openssl rand -base64 32`), confirm every service healthy, `GET http://localhost:${GATEWAY_PORT:-8080}/` returns the shell with the storefront CSP, `GET /api/v1/catalog/products` still works, `POST /` returns 404; record the cold-start time in `platform/docker/README.md` against the 3 min 58 s baseline (SC-009 first measurement); tear down afterwards

**Checkpoint**: Foundation ready. The gateway serves the (empty) storefront at `/` with cookie-based sessions available; the storefront package has its pure core, client, session store, shell and acceptance harness.

---

## Phase 3: User Story 1 - Browse the store in a browser (Priority: P1) 🎯 MVP

**Goal**: Anonymous visitors list products, browse categories, page, search and open product pages through the storefront at the same origin as the API.

**Independent Test**: With the seeded platform running, open `/`, a category, a search and a product page; items, prices, images and availability match the public API; out-of-stock is not addable; unknown product shows the not-found page; URL carries page/category/search state (quickstart §4 steps 1–5).

### Tests for User Story 1

- [X] T039 [P] [US1] Pact consumer `frontend/pact/catalog.pact.test.ts` → `build/pacts/storefront-catalog.json` with interactions C1–C5 of `contracts/pact-matrix.md` (provider states verbatim), driving `frontend/src/api/catalog.ts`
- [X] T040 [P] [US1] Component tests `frontend/tests/ui/browse.test.tsx` (MSW): home lists name, formatted price, primary image, availability and categories; category page shows only that category's products with pager and `?page=`/`?size=` in the URL; search renders results in API order, empty state for no match, `q` trimmed and capped at 100 chars; product page disables "Add to cart" when out of stock with an explanation; 404 renders `NotFoundPage`; loading, error (retry) and throttled (countdown) states; invalid UUID in the path renders not-found without a request
- [X] T041 [P] [US1] Acceptance feature `frontend/acceptance/features/catalogue-browsing.feature` (`@us1`) reusing the wording of `acceptance/src/test/resources/features/catalogue-browsing.feature`: list a category, open a product, search, out-of-stock product, unknown product link, shared category link reload; keyboard-only scenario reaching a product page; steps in `frontend/acceptance/steps/browsing.steps.ts`

### Implementation for User Story 1

- [X] T042 [P] [US1] Implement `frontend/src/api/catalog.ts` (typed wrappers `listProducts` with `page`, `size`, `q`, `categoryId`; `listCategories`; `getCategory`; `getProduct`) and `frontend/src/app/catalog/{useProducts,useCategories,useProduct}.ts` (TanStack Query keys include every URL parameter) until T039 passes
- [X] T043 [US1] Implement pages `frontend/src/ui/pages/{HomePage,CategoryPage,SearchPage,ProductPage}.tsx` and components `frontend/src/ui/components/{ProductCard,ProductGrid,Pager,CategoryNav,SearchBox}.tsx` with URL-driven state via React Router search params (`page` zero-based, `size` default 20), all text rendered as text (FR-015), labelled controls, visible focus, 360–1440 px layout (`frontend/src/ui/pages/*.module.css`), until T040 passes
- [X] T044 [US1] Register the routes `/`, `/categories/:slugOrId`, `/search`, `/products/:id` in `frontend/src/ui/routes/router.tsx` (UUID validation before loading; slug prefix accepted and ignored), run `npm run acceptance` against the stack with `STOREFRONT_URL=http://localhost:${GATEWAY_PORT:-8080}` until T041 passes with zero critical/serious axe violations, then `./gradlew -q verify`

**Checkpoint**: User Story 1 is demonstrable end to end in a browser (MVP).

---

## Phase 4: User Story 2 - Build a cart and check out (Priority: P1)

**Goal**: Anonymous cart that survives reloads and restarts, registration with email verification, sign-in with merge, checkout with address and simulated payment method, confirmation with both statuses, price-change acknowledgement, idempotent resubmission, pending-payment countdown, session-expiry recovery.

**Independent Test**: quickstart §4 steps 6–16: add/change/remove lines, reload and restart the browser, register, verify via Mailpit, sign in, see the merge, check out with the approving method, repeat with the declining and pending methods, double-submit yields one order, expire the session mid-checkout and return to the same step.

### Provider change (order) for User Story 2

- [X] T045 [P] [US2] Add `paymentExpiresAt` (date-time, present iff `paymentStatus` is `pending`) to the `Order` schema in `specs/004-ecommerce-platform-mvp/contracts/openapi/order.yaml` and `contracts/openapi/order.yaml` (additive; description: "end of the payment window configured by the order service"), and to the order's own provider state fixtures
- [X] T046 [US2] Implement `paymentExpiresAt` in the order service: expose the existing payment window deadline (`Order.place(placement, paymentWindow)` already computes it; add it to the order read model and HTTP response in `services/order/infrastructure/src/main/kotlin/.../web/OrderResponses.kt` or equivalent), property test in `services/order/domain/src/test/...` that the value equals placement time plus the configured window and is absent once payment is final, contract conformance check in `services/order/infrastructure/src/integrationTest/.../OrderContractConformanceIT.kt`; `./gradlew -q :services:order:infrastructure:integrationTest`

### Tests for User Story 2

- [X] T047 [P] [US2] Pact consumer `frontend/pact/cart.pact.test.ts` → `build/pacts/storefront-cart.json` (K1–K7) driving `frontend/src/api/cart.ts`
- [X] T048 [P] [US2] Pact consumer `frontend/pact/identity.pact.test.ts` → `build/pacts/storefront-identity.json` with the identity interactions this story uses (I1–I5, I7, I10, I11; the remaining identity rows are added in US4 T090) driving `frontend/src/api/identity.ts`
- [X] T049 [P] [US2] Pact consumer `frontend/pact/order.pact.test.ts` → `build/pacts/storefront-order.json` with O1–O5 and O7 (including `paymentExpiresAt` in O5 and O7) driving `frontend/src/api/order.ts`; `frontend/pact/payment.pact.test.ts` → `build/pacts/storefront-payment.json` with P2 (P1 added in US6 T110)
- [X] T050 [P] [US2] Property tests `frontend/tests/app/cart.test.ts`: `CartView` derivation from the cart contract (`priceChanged`, `unavailable` from catalog lookup or refused checkout, totals only from the server, `mergeNotice` from `cappedLines`), optimistic quantity update rolled back on 422, `Quantity.zero` maps to a remove
- [X] T051 [P] [US2] Property tests `frontend/tests/app/checkout.test.ts`: `CheckoutDraft` state machine of data-model.md §3.2 (reviewing → submitted → confirmed | refusedPriceChange → acknowledged → submitted; refused → reviewing; 401 → reviewing with the draft restored), idempotency rule (same key for a byte-identical body, new key when `cartRevision`, address id or payment method changes), `sessionStorage` persistence holds only `addressId`, `paymentMethodId`, `acknowledgedRevision`, `idempotencyKey`, `step` (fuzzed drafts never serialise an address text, email or token), the payment-method list contains exactly the seeded simulator tokens with labels
- [X] T052 [P] [US2] Component tests `frontend/tests/ui/cart.test.tsx` and `frontend/tests/ui/checkout.test.tsx` (MSW): add/change/remove lines with totals from the server, `priceChanged` lines show both prices, unavailable lines are flagged and excluded from checkout, empty cart state; checkout steps `?step=address|payment|review`, saved-address pick or new address saved first via identity then referenced by id, payment method options without any card field, review shows amounts; `409 price-changed` lists old/new prices and requires the explicit accept button before resubmit; `409 insufficient-stock` names lines; `422 payment-declined` explains and keeps the cart; pending shows "awaiting payment" with countdown from `paymentExpiresAt` and refetch every 5 s until final; double click sends one request; `Idempotency-Key` header reused on retry; 401 mid-checkout redirects to `/sign-in?next=/checkout?step=review` and restores the draft
- [X] T053 [P] [US2] Component tests `frontend/tests/ui/identity.test.tsx`: register (password ≥ 12 pre-check, server `errors[]` next to fields, generic 202 message identical for new and existing email), verify-email page reads `?token=` once, replaces history, shows success or one generic invalid/expired message; sign-in with generic invalid-credentials message, unverified (403) hint, throttled countdown, redirect to validated `next`, merge notice after sign-in
- [X] T054 [P] [US2] Acceptance features `frontend/acceptance/features/shopping-cart.feature` (`@us2`: add, change, remove, reload keeps the cart, new browser context with the same cookies keeps the cart, register and verify through Mailpit, sign in, merge summed up to stock) and `frontend/acceptance/features/checkout.feature` (`@us2`: approved checkout, price change acknowledgement after an operator price update via the API, insufficient stock, declined method `tok_sim_decline_0001`, pending method `tok_sim_unreachable` with countdown, double submit → one order, session expiry → return to checkout, keyboard-only checkout), steps in `frontend/acceptance/steps/{cart,identity,checkout}.steps.ts`

### Implementation for User Story 2

- [X] T055 [P] [US2] Implement `frontend/src/api/{cart,identity,order,payment}.ts` typed wrappers (cart: get/add/update/remove/clear/merge; identity: register, verifyEmail, signIn with `X-Browser-Session: cookie`, refresh, getOwnProfile, listOwnAddresses, addOwnAddress; order: placeOrder with `Idempotency-Key`, getOwnOrder; payment: listPaymentAttemptsForOrder) until T047–T049 pass
- [X] T056 [P] [US2] Implement `frontend/src/app/cart/{cartView,useCart}.ts` until T050 passes, and `frontend/src/domain/paymentMethods.ts` (build-time list of the seeded simulator tokens `tok_sim_approve_4242`, `tok_sim_decline_0001`, `tok_sim_unreachable` with shopper-facing labels marked "local development"), `frontend/src/app/checkout/{checkoutDraft,draftStorage,useCheckout}.ts` until T051 passes
- [X] T057 [US2] Implement `frontend/src/ui/pages/{CartPage,CheckoutPage,ConfirmationPage}.tsx` and components `frontend/src/ui/components/{CartLine,QuantityInput,AddressForm,AddressPicker,PaymentMethodPicker,OrderSummary,PaymentCountdown,PriceChangeNotice}.tsx`, add-to-cart on `ProductPage`, cart badge in `Layout`, until T052 passes
- [X] T058 [US2] Implement `frontend/src/ui/pages/{RegisterPage,VerifyEmailPage,SignInPage}.tsx` with `frontend/src/app/session/signIn.ts` (sign-in → merge → navigate to validated `next`) until T053 passes; confirm the link path in `services/notification/.../messaging/NotificationEvents.kt` `LinkPaths.VERIFY` equals `/verify-email` (adjust the storefront route if it differs; never change the token query parameter name used by `acceptance/.../MailpitClient.kt`)
- [X] T059 [US2] Register `/cart`, `/checkout`, `/orders/:id/confirmation`, `/sign-in`, `/register`, `/verify-email` in `frontend/src/ui/routes/router.tsx` (protected routes via the redirect rule), run `npm run acceptance` against the stack until T054 passes with zero critical/serious axe violations; run `./gradlew -q contractTest contractVerify` (identity, cart, order, payment verify the new pacts) and `./gradlew -q verify`

**Checkpoint**: Stories 1 and 2 give a complete shopper purchase in the browser (SC-003 measurable).

---

## Phase 5: User Story 3 - Initialise a development machine with one command (Priority: P1)

**Goal**: `scripts/dev-env.sh init` checks prerequisites with per-OS remediation, configures `.env`, secrets, hooks and engine quirks idempotently, optionally starts and smoke-checks the platform, with dry-run, opt-in installs, non-interactive safety and a runner-host mode. Independent of Phases 3–4 (different files).

**Independent Test**: quickstart §2: on a prepared clone `init --start` reaches a healthy platform and prints addresses; a rerun prints no `CHANGE` and exits 0 in under 10 s; with a stubbed missing tool `check` exits 3 with a fix line; `--dry-run` changes nothing; `scripts/tests/run-all.sh` passes offline.

### Tests for User Story 3 (offline, stubbed tools; contracts/dev-env-cli.md is the oracle)

- [X] T060 [P] [US3] Stub binaries in `scripts/tests/stubs/` for `docker`, `podman`, `docker-compose`, `java`, `node`, `npm`, `brew`, `apt-get`, `dnf`, `sudo`, `gitleaks`, `curl`, `jq`, `openssl`, `git`, `sdk`, each driven by environment variables (`STUB_JAVA_VERSION`, `STUB_ENGINE=docker|podman|none`, `STUB_ENGINE_MEMORY_GIB`, `STUB_COMPOSE_PS_JSON`, `STUB_PORT_IN_USE`, …) and appending every invocation to `$STUB_LOG`; helper `scripts/tests/lib/dev_env_fixture.sh` creating a temporary `HOME`, a temporary copy of `platform/compose/` with `.env.example`, and `PATH` with stubs first
- [X] T061 [P] [US3] `scripts/tests/test_dev_env_checks.sh`: all 16 checks PASS on a healthy stub set with the exact line format `printf '%-4s  %-10s %-12s   %s\n'`; each check FAILs on its stubbed failure with the documented `fix (macos)` / `fix (linux)` line; `podman` is `SKIP (engine is docker)` on Docker; `network` is `SKIP (no network)` never FAIL; summary line `checks: <n> total, <p> PASS, <f> FAIL, <s> SKIP`; exit 0 vs 3; whole `check` under 30 s with stubs (each probe ≤ 3 s timeout)
- [X] T062 [P] [US3] `scripts/tests/test_dev_env_init.sh`: configuration steps `env`, `secret`, `port`, `hooks`, `engine`, `ryuk` in that order with `OK`/`CHANGE`/`SKIP`/`DRY-RUN` lines; `.env` created mode 600 from the example; `IDENTITY_SIGNING_KEY` and `BROWSER_SESSION_KEY` generated only when empty and never replaced; `GATEWAY_PORT` proposal only when the default is busy by a foreign process; `git config core.hooksPath .githooks`; Podman writes `BUILDAH_FORMAT=docker` and, with consent, `ryuk.disabled=true`; `.env` not git-ignored → exit 3; interrupted write leaves no partial `.env` (temp file + move)
- [X] T063 [P] [US3] `scripts/tests/test_dev_env_idempotency.sh` and `scripts/tests/test_dev_env_dry_run.sh`: second `init` prints zero `CHANGE`, `.env` byte-identical and mtime unchanged, `~/.testcontainers.properties` unchanged; `--dry-run` prints one `DRY-RUN:` line per would-be mutation, writes nothing, runs no `git config` write, no install, no mutating Compose command, and a later real run still finds everything to do; `init` without `--start` under 10 s with stubs
- [X] T064 [P] [US3] `scripts/tests/test_dev_env_start.sh`: `init --start` runs `compose -p ecommerce-platform --profile core --profile observability up -d --build`, waits for health (stubbed `compose ps --format json`), prints the three smoke lines `entry`, `isolation`, `storefront` and the addresses block exactly as the contract; unhealthy component or failed smoke → exit 4; `--runner-host` adds the `ci` profile and the registry compose file, the `broker` and `ci-reg` smoke lines and the registration pointer line, never reads `ACCESS_TOKEN`, and `reset --runner-host` exits 2
- [X] T065 [P] [US3] `scripts/tests/test_dev_env_install.sh`: without `--install` missing tools are only reported; `--install` prints `INSTALL <tool>: <command>` before each run, uses `brew` on macOS and `apt-get`/`dnf` on Linux, never `brew install --cask docker` (decision printed), JDK via `sdk env install`; `sudo` commands only after the exact prompt answered `y`, `--yes` never answers it, non-interactive prints `manual: sudo <command>`; re-check after installs; remaining FAIL → exit 3
- [X] T066 [P] [US3] `scripts/tests/test_dev_env_non_interactive.sh` (stdin from `/dev/null` and `DEV_ENV_NON_INTERACTIVE=1`): no prompts, safe defaults, every skipped choice reported; `reset`/`down --volumes` without `--yes` → `ERROR … rerun with --yes`, exit 2; usage errors (unknown flag, flag not applicable, two subcommands) → exit 2 with usage on stderr; `-h` → usage on stdout exit 0
- [X] T067 [P] [US3] `scripts/tests/test_dev_env_secrets.sh` (SC-008): record transcripts of `init`, `init --dry-run`, `init --verbose`, `status` with generated keys and run `gitleaks detect --no-git` over them (skip with a clear message when `gitleaks` is absent locally; CI has it), and assert no `.env` value of a `*KEY*`, `*PASSWORD*`, `*TOKEN*` entry appears in any output

### Implementation for User Story 3

- [X] T068 [US3] Implement `scripts/lib/dev-env/{output.sh,checks.sh,config.sh,engine.sh,installs.sh,platform.sh}.sh` (sourcing `scripts/lib/common.sh` and `platform/compose/scripts/lib.sh`: reuse `detect_compose`, `ensure_env`, `wait_healthy`, `http_status`; extend `ensure_env` to generate `BROWSER_SESSION_KEY` too) and `scripts/dev-env.sh` (`init` default, `check`, `--install`, `--start`, `--yes`, `--dry-run`, `--runner-host`, `--verbose`, `-h`; exit codes 0/2/3/4; OS detection macos/debian/fedora; every mutation through the `run` wrapper for dry run; Compose always invoked with `-p ecommerce-platform`) until T061–T067 pass; `shellcheck -x` clean
- [X] T069 [US3] Extend `.github/workflows/platform.yml` "Shellcheck the platform scripts" step to include `scripts/*.sh`, `scripts/lib/**/*.sh`, `scripts/tests/*.sh` and add a step running `scripts/tests/run-all.sh` (offline, no engine) in the `verify` job of `.github/workflows/verify.yml` or `pr-gate.yml`, whichever runs on every pull request
- [ ] T070 [US3] Run the real thing on this machine: `scripts/dev-env.sh check`, `scripts/dev-env.sh init --dry-run`, `scripts/dev-env.sh init --start` (Podman path: `BUILDAH_FORMAT`, ryuk consent), time it (SC-001), rerun `init` (SC-002, SC-010), and record the results in `docs/validation/2026-10-storefront-run.md` §1; tear down with the existing Compose command (the `down` subcommand arrives in US5)
- [X] T071 [US3] Documentation: make `scripts/dev-env.sh init --start` the first step in `docs/running-locally.md` ("One-command start" becomes "Bootstrap", manual steps kept as the explanation), `CONTRIBUTING.md` "Set up your clone" (hooks activation now done by the script), `README.md` quick start, `docs/build.md` prerequisites; add `docs/dev-environment.md` with the command contract summary (subcommands, flags, exit codes, checks, what it never does), linking `specs/005-storefront-dev-bootstrap/contracts/dev-env-cli.md`

**Checkpoint**: A fresh clone reaches a running platform with one command; the bootstrap is tested offline and documented.

---

## Phase 6: User Story 4 - Manage the account and follow orders (Priority: P2)

**Goal**: Order list and detail with both statuses and history, shopper cancellation while `placed`, addresses and notification preferences, sign-out, forgot/reset password, account deletion.

**Independent Test**: quickstart §4 steps 17–22 with an account that has orders.

### Tests for User Story 4

- [ ] T072 [P] [US4] Extend `frontend/pact/identity.pact.test.ts` with I6, I8, I9, I12–I18 and `frontend/pact/order.pact.test.ts` with O6 and O8 (provider states verbatim from `contracts/pact-matrix.md`)
- [ ] T073 [P] [US4] Property tests `frontend/tests/app/orders.test.ts`: `OrderView` derivation and invariants of data-model.md §3.3 (`cancellationReason` iff `cancelled`; payment `approved` whenever `preparing`/`shipped`/`delivered`; `paymentDeadline` iff `pending`), `allowedActions` for role shopper (cancel only `placed`), `history[].by` rendered as "you"/"operator"/"system" never an account id, polling stops on final status or past deadline
- [ ] T074 [P] [US4] Component tests `frontend/tests/ui/account.test.tsx`: orders list newest first with both statuses and empty state; order detail with lines, amounts, address, history; cancel only offered when `placed`, confirmation dialog, `409 order-not-cancellable` shown; addresses CRUD with per-field 422 errors; notification preferences with `sms` only after phone verification (`422` shown); forgot-password shows the same confirmation whether or not the email exists; reset-password reads `?token=` once and replaces history; sign-out clears state and returns to `/`; account deletion requires the password, then signs out and shows the farewell state
- [ ] T075 [P] [US4] Acceptance features `frontend/acceptance/features/order-tracking.feature` (`@us4`: list, detail, cancel a placed order, cancel unavailable after `preparing` set by the operator via API) and `frontend/acceptance/features/account.feature` (`@us4`: addresses, preferences, forgot and reset password through Mailpit, sign-out, account deletion then sign-in refused), steps in `frontend/acceptance/steps/{orders,account}.steps.ts`

### Implementation for User Story 4

- [ ] T076 [P] [US4] Extend `frontend/src/api/identity.ts` (signOut, updateOwnProfile, deleteOwnAccount, updateOwnAddress, deleteOwnAddress, notification preferences, phone verification, requestPasswordReset, completePasswordReset) and `frontend/src/api/order.ts` (listOwnOrders, cancelOwnOrder), `frontend/src/api/payment.ts` (getPaymentAttempt) until T072 passes; implement `frontend/src/app/orders/{orderView,useOrders,useOrder}.ts` until T073 passes
- [ ] T077 [US4] Implement `frontend/src/ui/pages/{OrdersPage,OrderPage,AccountPage,AddressesPage,NotificationsPage,ForgotPasswordPage,ResetPasswordPage}.tsx` and components `frontend/src/ui/components/{OrderRow,StatusBadges,StatusHistory,PreferencesForm}.tsx`, sign-out action in `Layout`, until T074 passes
- [ ] T078 [US4] Register `/orders`, `/orders/:id`, `/account`, `/account/addresses`, `/account/notifications`, `/forgot-password`, `/reset-password` in `frontend/src/ui/routes/router.tsx`; confirm `LinkPaths.RESET_PASSWORD` in `services/notification/.../messaging/NotificationEvents.kt` is `/reset-password`; run `npm run acceptance` until T075 passes; `./gradlew -q contractTest contractVerify verify`

**Checkpoint**: The shopper's self-service loop is complete (feature 004 stories 3, 5 and 6 reproducible through the storefront).

---

## Phase 7: User Story 5 - Keep the local environment healthy (Priority: P2)

**Goal**: `status`, `update`, `reset` and `down` subcommands with the safety rules of `contracts/dev-env-cli.md`. Depends on US3.

**Independent Test**: quickstart §9: `status` shows every component healthy with addresses and engine resources; stop one service and see it `stopped`; `reset` asks the exact confirmation and reseeds; `update` after a change rebuilds only that component; `down` leaves nothing running and says `data kept`.

### Tests for User Story 5

- [X] T079 [P] [US5] `scripts/tests/test_dev_env_status.sh`: component table derived from stubbed `compose ps --format json` per data-model.md §5.4 (healthy/starting/unhealthy/stopped, services absent → stopped, no health check + running → healthy), only the `ecommerce-platform` project is listed, addresses block, engine resources line, then the checks and summary; exit 4 when a core component is not healthy while prerequisites pass, 3 wins over 4
- [X] T080 [P] [US5] `scripts/tests/test_dev_env_maintenance.sh`: `update` runs `up -d --build` keeping volumes, waits, smoke-checks, never `down`; `reset` prints the exact data-loss prompt text, only the answer `yes` continues (anything else → `aborted: nothing was changed`, exit 0), `--yes` prints `confirmed by --yes: deleting platform data`, then `down -v` scoped to the project, `up -d --build`, wait, checks; `down` keeps volumes and prints `data kept`, `down --volumes` requires the confirmation; `down` on a stopped platform prints `OK platform already down` exit 0; no `rm`/`stop`/`kill`/`prune` by name or filter ever appears in the stub log; `--dry-run` variants print `DRY-RUN:` lines only
- [X] T081 [P] [US5] Extend `scripts/tests/test_dev_env_non_interactive.sh` and `scripts/tests/test_dev_env_secrets.sh` to cover `status`, `update`, `reset --yes`, `down`

### Implementation for User Story 5

- [X] T082 [US5] Implement `status`, `update`, `reset`, `down` (with `--volumes`) in `scripts/dev-env.sh` and `scripts/lib/dev-env/platform.sh` (project-scoped Compose calls only, `compose ps --format json` parsing with `jq`, confirmation prompt verbatim, non-interactive refusal) until T079–T081 pass; `shellcheck -x` clean
- [ ] T083 [US5] Run `status`, `update` (after touching a storefront source file), `reset --yes`, `down` and `down --volumes --yes` on this machine against the real stack; record outputs in `docs/validation/2026-10-storefront-run.md` §2; document the four subcommands in `docs/dev-environment.md` and `docs/running-locally.md` (Teardown and Rebuild sections now point at the script)

**Checkpoint**: Daily environment loop covered by one script.

---

## Phase 8: User Story 6 - Fulfil orders and adjust stock from a minimal console (Priority: P3)

**Goal**: Operator-only console: list and filter all orders, advance and cancel orders with confirmation, adjust stock with a reason; no product or category editing; refusal enforced by the platform.

**Independent Test**: quickstart §5: sign in as the seeded operator, advance an order `placed → preparing → shipped`, cancel one, raise stock on a sold-out product and see it addable again; a shopper opening `/console` gets the "not allowed" state and the platform's 403; no editing controls exist.

### Tests for User Story 6

- [ ] T084 [P] [US6] Extend `frontend/pact/catalog.pact.test.ts` with C6 and C7, `frontend/pact/order.pact.test.ts` with O9–O11, `frontend/pact/payment.pact.test.ts` with P1 (operator 200 and shopper 403)
- [ ] T085 [P] [US6] Property tests `frontend/tests/app/console.test.ts`: `allowedActions` for role operator (`cancel` for `placed`/`preparing`; `advance(preparing)` only with payment `approved`; `advance(shipped)`, `advance(delivered)`; none for terminal), `ConsoleOrderFilter` (client-side status filter within the page, `page`/`size` 1..100 in the URL), `StockAdjustment` (`delta` non-zero integer, `reason` 1..255 required, reason never reaches telemetry attributes)
- [ ] T086 [P] [US6] Component tests `frontend/tests/ui/console.test.tsx`: console link visible only with role `operator`; a signed-in shopper at `/console/orders` sees "not allowed" and the platform's 403 is surfaced without data; orders table with both statuses and `?status=` filter; order page offers only allowed transitions, confirmation dialog, `409 invalid-transition` shown without changing the displayed status; cancel with confirmation; stock page searches products (`includeWithdrawn` for visibility), adjustment form with `422` next to `delta`; no create/edit/withdraw control exists and the page states catalogue editing is API-only
- [ ] T087 [P] [US6] Acceptance feature `frontend/acceptance/features/console.feature` (`@us6`: operator advances and cancels, stock adjustment makes a sold-out product addable, shopper refused, no catalogue editing offered) in `frontend/acceptance/steps/console.steps.ts`; extend `acceptance/src/test/resources/features/authorisation-sweep.feature` with a scenario that a shopper token is refused on `adjustStock` and `transitionOrderStatus` (behaviour wording, steps in `acceptance/src/test/kotlin/.../AuthorisationSteps.kt`)

### Implementation for User Story 6

- [ ] T088 [P] [US6] Extend `frontend/src/api/catalog.ts` (adjustStock, `includeWithdrawn`), `frontend/src/api/order.ts` (transitionOrderStatus, operator list), `frontend/src/api/payment.ts` (getSimulatorRules) until T084 passes; implement `frontend/src/app/console/{consoleOrders,stockAdjustment}.ts` until T085 passes; additive order provider change for the operator listing (plan.md "Operator order listing": `GET /api/v1/orders` accepts role `operator` and returns all orders with an optional `orderStatus` filter, both OpenAPI copies, application-layer authorisation, `OrderContractConformanceIT`, provider state O9 and the new O11 state `order 0b9a3b0e-... is placed with payment pending`)
- [ ] T089 [US6] Implement `frontend/src/ui/console/{ConsoleLayout,ConsoleOrdersPage,ConsoleOrderPage,ConsoleStockPage}.tsx` and components `frontend/src/ui/console/{OrdersTable,TransitionButtons,StockAdjustmentForm}.tsx` until T086 passes
- [ ] T090 [US6] Register `/console/orders`, `/console/orders/:id`, `/console/stock` (operator-protected) in `frontend/src/ui/routes/router.tsx`; run `npm run acceptance` and `./gradlew -q :acceptance:test` (with `GATEWAY_URL`) until T087 passes; `./gradlew -q contractTest contractVerify verify`

**Checkpoint**: All six user stories are independently functional.

---

## Phase 9: Browser telemetry and observability (cross-cutting: FR-031, FR-032, SC-004, SC-011)

**Purpose**: Browser spans, errors, timings, navigation and interaction events reach the existing collector through the gateway with correlation ids and without personal data; dashboards show storefront performance.

- [X] T091 [P] Gateway integration test `services/gateway/src/integrationTest/kotlin/com/ecommerce/gateway/TelemetryRouteIT.kt` (WireMock collector): `POST /api/v1/telemetry/v1/traces` and `/logs` forwarded to `/v1/traces` and `/v1/logs` without `Cookie` or `Authorization`, 200 body passed through, 400 passed through, body > 256 KiB → 413 `payload-too-large`, `browse` tier → 429 with `Retry-After`, collector down → 503 `unavailable`, `GET` → 404; then make it pass by completing the route configuration from T023 (`RemoveRequestHeader`, size limit, no retry)
- [x] T092 [P] Property tests `frontend/tests/telemetry/policy.test.ts` (fast-check): the attribute allow-list of data-model.md §3.5 is exactly {route template, http method, status code, element role/id, duration ms, error name, correlation id, session id}; fuzzed inputs containing query strings, form values, search terms, free text, account ids, emails, tokens and cookie values never appear in any exported attribute; URLs are reduced to `RouteTemplate`; `error name` is a class name only; events for a 429 pause the exporter for `Retry-After`; a failed export is dropped, never retried in a loop, never throws into the page; `session id` is a random UUID in `sessionStorage` distinct from any credential
- [x] T093 Implement `frontend/src/telemetry/{setup,policy,exporter,routeTemplates}.ts` with `@opentelemetry/sdk-trace-web`, `@opentelemetry/instrumentation-document-load`, `@opentelemetry/instrumentation-fetch` (propagates `traceparent`, adds `correlation.id`), `@opentelemetry/instrumentation-user-interaction` (click, submit, keydown Enter; element role/id only), `@opentelemetry/sdk-logs` for `error` events (window `error`/`unhandledrejection`, failed actions), resource `service.name=storefront`, `service.version` from `package.json`, `session.id`; OTLP/HTTP JSON exporters to `/api/v1/telemetry/v1/{traces,logs}` wrapped by the allow-list policy; initialise in `frontend/src/main.tsx`; until T092 passes
- [x] T094 [P] Collector second layer in `platform/observability/otel-collector.yaml`: `attributes/storefront` processor deleting `http.url`, `http.target`, `url.full`, `url.query`, `user.*`, `enduser.*` and a `redaction` processor (allow-list of the eight attributes plus semantic resource keys) applied only when `service.name == storefront` (routing connector or `filter`+pipelines), and `yamllint` clean; update `platform/compose/README.md` observability data-flow section
- [x] T095 [P] Grafana dashboard `platform/observability/grafana/dashboards/storefront-rum.json` (provisioned like `service-red.json`): page-load p95 and action p95 by route template (Tempo spans of `service.name=storefront`), client error rate (Loki `service="storefront"`), throttled exports, SC-004 thresholds drawn at 2 s and 1 s; link from "Requests by correlation id" rows to the browser span
- [x] T096 [P] Extend `frontend/pact/gateway.pact.test.ts` with G16–G17 and verify with `./gradlew -q contractTest contractVerify`
- [x] T097 Platform acceptance scenario in `acceptance/src/test/resources/features/platform-observability.feature`: "a shopper's page view and the services it reaches share one correlation id in the logs and one trace" (behaviour wording; steps in `acceptance/src/test/kotlin/.../ObservabilitySteps.kt` drive a browser page view through Playwright-less means: the step issues the storefront's requests with the same headers the storefront sends and queries Loki/Tempo via Grafana's datasource proxy as the existing steps do), plus a privacy assertion scenario: no storefront log or span attribute contains the test shopper's email, address or search term (SC-011)
- [X] T098 Run the stack with the observability profile, browse and check out once, and verify in Grafana: storefront RUM dashboard populated, correlation id joins browser span, gateway access line and service lines; stop the `observability` profile and confirm the storefront keeps working while exports are dropped silently; record in `docs/validation/2026-10-storefront-run.md` §3 and document the telemetry design in `docs/storefront.md` (new) and `docs/gateway.md` (telemetry routes, browser session, per-route CSP, cookie names by transport, `BROWSER_SESSION_KEY`)

**Checkpoint**: Browser telemetry flows end to end with the privacy guard rails verified.

---

## Phase 10: Polish, CI and documentation

**Purpose**: Pipelines, mutation thresholds, remaining docs, and the full quickstart validation.

- [X] T099 [P] Create `.github/workflows/storefront.yml` mirroring the service pipelines (path filter `frontend/**`, `platform/docker/Dockerfile.storefront`, `platform/docker/storefront/**`, the two 005 contracts; jobs: `gate` = `npm ci`, `lint`, `test`, `mutate` (Stryker ≥ 80 %), `pact` + publish `storefront-*` pacts to the Pact Broker with the commit as version and `can-i-deploy`; `osv-scanner` on `frontend/package-lock.json` (critical blocks); `image` = build `Dockerfile.storefront`, Trivy (CRITICAL fails), syft SBOM, `.github/scripts/image-health.sh storefront <image>`; `publish` on `main` to the private registry), add `service-ci / storefront` to `.github/workflows/required-checks.yml` and the path-filter check script, and document in `docs/ci-cd.md`
- [X] T100 [P] Add `frontend/**` to the platform workflow's acceptance job: after the JVM suite, `npm ci --prefix frontend` and `npm --prefix frontend run acceptance` with `STOREFRONT_URL=$GATEWAY_URL` and `MAILPIT_URL`, Playwright Chromium installed via `npx playwright install --with-deps chromium` on the runner image (document in `platform/ci-runner/README.md` host requirements)
- [X] T101 [P] Verify the harness hooks pick up the storefront: `.claude/hooks/stop-full-check.sh` runs Stryker incremental now that `frontend/stryker.config.json` exists, `pr-gate.yml` installs frontend dependencies for Stryker; adjust `docs/harness.md` ("Stryker is deferred" paragraphs become active) and add a mutation baseline entry for the storefront in `quality/mutation-baseline.json` if the harness expects one
- [X] T102 [P] Add the storefront to `docs/architecture.md` (component diagram: browser → gateway → storefront container / services / collector; browser-session and cart cookies; telemetry path; Known deviations: build-time payment-method list, client-side console filter, no slug for categories) and the auth tier description; add an ADR `docs/adr/0005-browser-session-at-the-gateway.md` (decision, alternatives from research §2)
- [X] T103 [P] Update `contracts/gateway-routes.md` and `contracts/pact-matrix.md` of feature 004 (append the 005 rows with pointers), `docs/service-conventions.md` section 6 (storefront pacts, `build/pacts/storefront-*.json`), `platform/perf/README.md` (note that k6 bypasses the storefront; browser SC-004 is measured by telemetry)
- [ ] T104 Cold-start measurement for SC-009: from a pruned build cache (`docker builder prune -f` for this project's cache only), time `docker compose --profile core --profile observability up -d --build` with the storefront image under `COMPOSE_PARALLEL_LIMIT=1`; record the figure next to the 3 min 58 s baseline in `platform/docker/README.md` and `docs/validation/2026-10-storefront-run.md` §4; if above 5 min, optimise the Node build stage (npm cache mount, `--omit=dev`) until it fits
- [ ] T105 Full quickstart validation: execute `specs/005-storefront-dev-bootstrap/quickstart.md` §2–§11 end to end on this machine (bootstrap timing SC-001/SC-002, shopper journey under 5 min SC-003, SC-005 scenario coverage via the acceptance suites, axe SC-006, contracts SC-007, secrets SC-008, idempotency SC-010, telemetry SC-004/SC-011), record every observation in `docs/validation/2026-10-storefront-run.md` §5 with the outcomes table, and update `specs/005-storefront-dev-bootstrap/spec.md` status to reflect verified criteria (status line only)
- [ ] T106 Final gate: `./gradlew -q verify` (silent, includes `frontendCheck`), `./gradlew -q contractTest contractVerify`, `npm --prefix frontend run mutate` (≥ 80 %), `scripts/tests/run-all.sh`, `shellcheck -x scripts/*.sh scripts/lib/**/*.sh scripts/tests/*.sh platform/compose/scripts/*.sh`; fix anything red; confirm the working tree has no generated files committed (`frontend/src/api/generated/`, `frontend/dist/`)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies; T001 first, T002–T010 and T012 in parallel, T011 last.
- **Foundational (Phase 2)**: depends on Phase 1. Three independent tracks run in parallel: gateway (T013–T024), storefront core and shell (T025–T034), image and Compose (T035–T036); T037–T038 close the phase. **Blocks Phases 3, 4, 6, 8, 9.** Phase 5 (bootstrap) depends only on Phase 1 T012 and T036 (the `storefront` smoke check) and can start as soon as Phase 1 is done, using the Compose file as it evolves.
- **US1 (Phase 3)**: after Phase 2. No story dependencies.
- **US2 (Phase 4)**: after Phase 2 (browser session and cart cookies). T045–T046 (order provider) can run in parallel with the storefront tasks of the phase.
- **US3 (Phase 5)**: after Phase 1; independent of Phases 2–4 except the `storefront` smoke line (T036).
- **US4 (Phase 6)**: after US2 (sign-in, orders exist).
- **US5 (Phase 7)**: after US3.
- **US6 (Phase 8)**: after US2 (session) and US4 (order view model reuse).
- **Telemetry (Phase 9)**: after Phase 2; T091 and T094–T095 can start with Phase 3; T097–T098 need a working shopper journey (US2).
- **Polish (Phase 10)**: after every story the team wants shipped; T099–T103 are parallel documentation/CI tasks, T104–T106 last.

### User Story Dependencies

- **US1 (P1)**: Phase 2 only. MVP.
- **US2 (P1)**: Phase 2; integrates with US1's product page (add to cart).
- **US3 (P1)**: Phase 1 only; fully independent files (`scripts/`, docs).
- **US4 (P2)**: US2.
- **US5 (P2)**: US3.
- **US6 (P3)**: US2 and US4; droppable without affecting the others (spec clarification).

### Within Each User Story

- Pact consumer and property tests first (they fail until the client, model or page exists), then `api/` wrappers, then `app/` models, then `ui/` pages, then routes and acceptance.
- Provider verification (`./gradlew -q contractTest contractVerify`) closes every story that added pact interactions.
- Every story ends with `./gradlew -q verify` silent.

### Parallel Execution Examples

- **Phase 2, three agents**: gateway browser session (T013–T020, then T021–T024) / storefront core and shell (T025–T034) / image, Compose and smoke (T035–T036, then T038 after both others). Worktrees as in feature 004; one Gradle build per tree.
- **After Phase 2, three agents**: US1 (T039–T044) / US3 bootstrap (T060–T071) / telemetry gateway and collector (T091, T094, T095). Then US2 (T045–T059) with the order provider change (T045–T046) in a fourth, short-lived tree.
- **Within US2**: T047, T048, T049 (pacts), T050, T051 (property tests), T052, T053 (component tests), T054 (features) are all parallel before T055–T059.
- **Within US3**: T060 first (stubs), then T061–T067 in parallel, then T068.
- **Phase 10**: T099, T100, T101, T102, T103 in parallel; T104, T105, T106 sequential.

## Implementation Strategy

1. **MVP** = Phase 1 + Phase 2 + Phase 3 (US1): the storefront serves browsing at the platform's single origin with the session and cart machinery in place at the gateway. Demonstrable to stakeholders and already covered by the quality gate.
2. **Revenue increment** = Phase 4 (US2): a shopper can buy in the browser; SC-003 measurable.
3. **Developer increment** = Phase 5 (US3), runnable in parallel with 1–2 by a separate agent since it touches only `scripts/` and docs.
4. **Self-service and daily loop** = Phases 6–7 (US4, US5).
5. **Console** = Phase 8 (US6), optional per the clarification; skipping it removes T084–T090 only.
6. **Observability and release** = Phases 9–10.

Task count: 106 (Setup 12, Foundational 26, US1 6, US2 15, US3 12, US4 7, US5 5, US6 7, Telemetry 8, Polish 8).
