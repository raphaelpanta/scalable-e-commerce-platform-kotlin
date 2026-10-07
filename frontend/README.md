# storefront

The web storefront of the platform (TypeScript + React, constitution Principle VII), feature
`specs/005-storefront-dev-bootstrap`. A Vite-built single-page application served by the gateway at
the same origin as the API; it never holds a token (the gateway keeps the credentials in an
HttpOnly cookie, `X-Browser-Session: cookie` on every request).

## Layers

| Directory       | Role                                                                                                                                                                                                        | May import                                    |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| `src/domain`    | Pure value objects and rules (`Email`, `Password`, `Quantity`, `Money`, `Address`, `CartRevision`, `CorrelationId`, `IdempotencyKey`, `OrderStatus`/`PaymentStatus` transitions, `RouteTemplate`, `Result`) | nothing else; no browser globals              |
| `src/app`       | Use cases: session store and hook, correlation ids, `safeNext` redirect rule, query client defaults                                                                                                         | `domain` (the API edge is injected as a port) |
| `src/api`       | The HTTP edge: `openapi-fetch` client over the generated contract types, problem mapping, the session port adapter                                                                                          | `domain`, `app` ports, `generated/`           |
| `src/telemetry` | Browser telemetry (placeholder until phase 9)                                                                                                                                                               | `domain`                                      |
| `src/ui`        | React: `routes/` (data router), `pages/`, `components/`, `styles/` (tokens + CSS Modules)                                                                                                                   | everything                                    |

ESLint `no-restricted-imports` and `no-restricted-globals` enforce the table (`eslint.config.js`).
`src/api/generated/` is produced by `npm run generate` from the OpenAPI contracts and is
git-ignored: **hand-written response types are forbidden**; `npm run lint` fails when the
generated files are missing or stale (`scripts/generate-api.mjs --check`). `lint`, `test`, `build`,
`dev`, `mutate` and `pact` generate the files first when they are absent, so a fresh clone and the
Gradle `frontendLint`/`frontendTest` tasks need no manual step.

Inputs of the generator: `specs/004-ecommerce-platform-mvp/contracts/openapi/{identity,catalog,cart,order,payment}.yaml`
and `specs/005-storefront-dev-bootstrap/contracts/openapi/{gateway-browser-session,telemetry}.yaml`.

## Scripts

All scripts are quiet on success and non-interactive (`CI=1` friendly); the root build runs the
first two through `./gradlew -q frontendCheck` (part of `verify`).

| Script               | Does                                                                                                    |
| -------------------- | ------------------------------------------------------------------------------------------------------- |
| `npm run lint`       | `prettier --check`, `eslint --max-warnings 0`, `tsc --noEmit`, generated-types freshness                |
| `npm test`           | Vitest unit and component suite (`tests/`), dot reporter, failures only                                 |
| `npm run generate`   | Regenerates `src/api/generated/*.d.ts`                                                                  |
| `npm run build`      | Production bundle in `dist/` (hashed assets under `dist/assets/`, no inline script or style)            |
| `npm run dev`        | Vite dev server on 5173, `/api` proxied to `http://localhost:${GATEWAY_PORT:-8080}`                     |
| `npm run acceptance` | Cucumber.js + Playwright scenarios in `acceptance/`; skipped with exit 0 unless `STOREFRONT_URL` is set |
| `npm run mutate`     | Stryker over `src/domain`, `src/app`, `src/telemetry` (break at 80 %)                                   |
| `npm run pact`       | Pact JS consumer tests `pact/*.pact.test.ts`, writing `../build/pacts/storefront-<provider>.json`       |

Acceptance environment: `STOREFRONT_URL` (the gateway, for example `http://localhost:8080`),
`MAILPIT_URL` (default `http://localhost:8025`), `OPERATOR_EMAIL`/`OPERATOR_PASSWORD` (feature 004
seed defaults), `CUCUMBER_TAGS` (for example `@us1`), `STOREFRONT_VIEWPORT=mobile` to run every
scenario at 360×780 instead of 1280×800, `HEADED=1` to watch the browser. Every page a scenario
visits is audited with axe; `critical` and `serious` violations fail the scenario. Install the
browser once with `npx playwright install chromium`.

Unit tests: `FC_SEED=<n>` pins the fast-check seed (printed on failure), `FC_NUM_RUNS` the run count.

## Quality gate

`./gradlew -q verify` runs `frontendLint` and `frontendTest` (`npm --silent --prefix frontend run lint|test`)
as soon as `frontend/package.json` exists; both must stay silent on success. Use
`-PnpmExecutable=/absolute/path/to/npm` when `npm` is not on the Gradle daemon's PATH. The hooks run
Stryker incrementally because `stryker.config.json` exists; CI runs it in full. Tests come first:
property tests (fast-check) for `domain` and `app`, component tests (Testing Library + MSW, every
unhandled request is an error) for `ui`, Pact consumer tests per provider, Cucumber scenarios
written as user behaviour.

## Decisions recorded at implementation time (2026-10-04)

- **TypeScript 5.9** (not 6.x/7.x): `typescript-eslint` 8.71 supports `< 6.1` and `openapi-typescript`
  declares `^5.x`; TypeScript is bumped when both support the newer line.
- **ESLint 9.39** (not 10.x): `eslint-plugin-jsx-a11y` 6.10 does not yet declare ESLint 10 as a peer.
- **React Router 8**: the data-router API (`createBrowserRouter`, `RouterProvider` from
  `react-router/dom`, loaders, `replace()` redirects) is unchanged from v7; no `react-router-dom`.
- **MSW 3**: `server.listen({ onUnhandledFrame: 'error' })` replaces v2's `onUnhandledRequest`.
- **TanStack Query 5.104**: `queryClient.query(...)` replaces the deprecated `fetchQuery`/`ensureQueryData`.
- **Route templates**: `src/domain/routeTemplate.ts` is the closed list of data-model.md §2.2 plus
  the three routes the route table defines and §2.2 omits (`/orders/:id/confirmation`,
  `/account/addresses`, `/account/notifications`); the router registers exactly this list.
- **Vitest 5 / Vite 8 / Stryker 10 / Pact JS 17 / Cucumber.js 13 / Playwright 1.63** at their latest
  stable, pinned exactly in `package-lock.json` (`save-exact` in `.npmrc`, `engine-strict` for Node 24).

## Browsing (US1, 2026-10-06)

- **Catalogue port**: `src/app/catalog/catalogPort.ts` declares what browsing needs (`listProducts`,
  `listCategories`, `getCategory`, `getProduct`, generated types only); `src/api/catalog.ts`
  implements it and the composition root (`main.tsx`, the test harness) injects it through
  `CatalogPortContext`, like the session port. A 404 on a detail lookup is `null` (unknown or
  withdrawn), never an error state; the pages render `NotFoundPage` for it.
- **URL state** (`src/app/catalog/browseParams.ts`): `page` zero-based as the API; `size` only
  within 1..100 and only when it is not the default 20 (anything else is ignored, the platform's
  default applies); `q` trimmed, cut at 100 characters and trimmed again. Query keys carry every
  parameter (`['catalog', 'products', {page, size, q, categoryId}]`, absent ones as `null`).
- **Category links** are `/categories/<slug>-<uuid>`; the route accepts a UUID with or without a
  readable prefix (`catalogIdFromParam`), resolution is by id only; any other value renders
  not-found without a request, as does a non-UUID product id.
- **States**: `QueryBoundary` renders loading, throttled (countdown) and error (retry, support
  details) for any query; `ProductListing` adds the empty states (first page: the page's own
  message and one action; a page past the end: "back to the first page").
- **Add to cart** on the product page is rendered (disabled with an explanation when out of stock)
  and wired to a no-op `onAddToCart` prop until US2 lands.
- **Acceptance fixtures** (`acceptance/support/catalogue.ts`) are created through the public API
  as the seeded operator with a bearer token, like the JVM suite; names carry a random suffix.

## Vitest version pin

Vitest is pinned to 4.1.x: with Vitest 5.0 the Stryker Vitest runner (10.0.0) records no per-test coverage, so every mutant survives with "0.00 tests per mutant" and the mutation score is 0. Upgrade Vitest only once a Stryker release declares support for it (verify with `npm run mutate`).
