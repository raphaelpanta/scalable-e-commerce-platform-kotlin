# Quickstart: Validate the Web Storefront and Local Development Bootstrap

This is a validation guide: it bootstraps a fresh clone with one command, opens the storefront, walks the
shopper and operator journeys in a browser and maps what you should observe to the success criteria SC-001 to SC-011
in [spec.md](./spec.md). Design decisions are in [research.md](./research.md) and [plan.md](./plan.md); schemas, routes
and the command contract are not repeated here, see [data-model.md](./data-model.md),
[contracts/dev-env-cli.md](./contracts/dev-env-cli.md), [contracts/storefront-routes.md](./contracts/storefront-routes.md),
[contracts/gateway-routes.md](./contracts/gateway-routes.md), [contracts/pact-matrix.md](./contracts/pact-matrix.md) and
[contracts/openapi/](./contracts/openapi/). Platform facts (seed data, payment tokens, observability) come from
feature 004's [quickstart](../004-ecommerce-platform-mvp/quickstart.md) and `docs/running-locally.md`.

Run every command from the repository root unless stated otherwise.

## 1. Prerequisites

`scripts/dev-env.sh check` verifies all of these, prints `PASS`, `FAIL` or `SKIP (no network)` with the found and the
expected value and, for each failure, the remediation for your operating system (macOS or Linux; Windows only through WSL):

| Prerequisite | Expected |
|--------------|----------|
| JDK | the version pinned in `.java-version` (JDK 25), for the quality gate |
| Container engine | Docker Engine/Desktop or Podman, reachable, with Compose v2; engine VM with at least 10 GiB memory, 4 CPUs and 15 GiB free disk |
| Node and npm | Node 24 (`frontend/.nvmrc`) with npm |
| `gitleaks` | present (the secret-scanning hook and SC-008 use it) |
| `curl`, `jq`, `openssl` | present |
| Gateway port | `GATEWAY_PORT` (default 8080) free |

`check` changes nothing and exits 0 (all good) or 3 (something missing). A machine missing a tool reports every missing
item in under 30 seconds (SC-002).

## 2. Bootstrap a fresh clone (US3, SC-001, SC-002, SC-008, SC-010)

```bash
git clone <repository> && cd scalable-e-commerce-platform-kotlin
time scripts/dev-env.sh init --start
```

Expected output, in order: one `PASS` line per check; the configuration steps (`.env` created from
`platform/compose/.env.example`; `IDENTITY_SIGNING_KEY` and `BROWSER_SESSION_KEY` generated because they were empty;
`git config core.hooksPath .githooks`; on Podman the `BUILDAH_FORMAT=docker` setting and, with your consent, the
`~/.testcontainers.properties` Ryuk setting); then the image build and start of the `core` and `observability` profiles,
the wait until every component is healthy, the smoke checks (gateway answers, no service port published, `GET /` returns
the storefront) and the address table (storefront and gateway, Grafana, Mailpit). The two keys are written to the
git-ignored `.env` and are never printed.

| Observation | Success criterion |
|-------------|-------------------|
| `init --start` on a cold machine, including the image build, finishes in under 30 minutes without reading any document | SC-001 |
| Second `scripts/dev-env.sh init` prints "nothing to do" for every step and finishes in under 10 seconds; `.env` is byte-identical | SC-002, SC-010 |
| `scripts/dev-env.sh init --dry-run` prints every change it would make, makes none (compare `git status` and `.env` before and after), checks still run | SC-010 |
| On a machine missing a tool, `init` lists every missing item with remediation and exits 3 without changing anything (`echo $?`) | SC-002 |
| Secrets absent from output: save the transcript with `scripts/dev-env.sh init --start 2>&1 \| tee /tmp/init.log`, then `gitleaks detect --no-git --source /tmp/init.log` and `gitleaks detect` on the working tree report no leaks | SC-008 |

Without a terminal (or with `--yes`) decisions that need consent take the safe default (no install, no data loss) and the
choice is reported. `--install` is opt-in: each tool is announced before its package-manager command runs and `sudo`
is only used after an explicit prompt.

## 3. Open the storefront

Open `http://localhost:${GATEWAY_PORT:-8080}/`: the storefront is served by the gateway at the same origin as the API
(`/api/**`), so there is no second port. Other addresses: Grafana http://localhost:3000 (user `admin`, password
`GRAFANA_ADMIN_PASSWORD` from `.env`), Mailpit http://localhost:8025. If you use Podman, the script sets
`BUILDAH_FORMAT=docker` for you (otherwise the image `HEALTHCHECK` is dropped and nothing becomes healthy).

## 4. Shopper journey in the browser (US1, US2, US4; SC-003, SC-005)

Use a fresh private window and time the whole walk (target under 5 minutes, SC-003). Keep DevTools open on
Application and Network. Every step below corresponds to a Gherkin scenario in `frontend/acceptance/features/`
(tags `@us1` to `@us6` reuse feature 004's wording), so 100 % of feature 004's shopper scenarios can be reproduced
through the storefront (SC-005).

**Browse (US1).**
1. Home lists active products with name, price, image and availability, and the three seeded categories.
2. Open a category, go to page 2, then search for a product name. The address bar carries category, page and query,
   so reload, back and a copied link show the same view. Withdrawn products never appear; an unmatched term shows
   "nothing found".
3. Open a product with zero stock (reduce one in section 5, or use any sold-out product): it says out of stock and
   "add to cart" is unavailable.
4. Open `/products/00000000-0000-0000-0000-000000000000`: a "product not found" page with a way back, not an error dump.
5. Stop the catalog (`docker compose --profile core stop catalog`, from `platform/compose`) and reload a category: loading
   state, then a readable error with a retry action. Start it again and retry.

**Anonymous cart (US2).**
6. Add two products, change one quantity, remove the other. Totals update immediately.
7. Reload, then close and reopen the browser: the cart is still there. In DevTools, Application, Cookies, the cart
   cookie (`__Host-cart` over HTTPS, `cart` on plain http, see section 12) is `HttpOnly`; Local Storage and Session
   Storage hold no token (only the random telemetry session id and, later, the checkout draft ids).

**Register, verify, sign in.**
8. Register with a new email and password. The response is the same wording for known and unknown emails.
9. In Mailpit open the verification email; its link points at the storefront on your configured port. Open it: the
   storefront confirms the verification.
10. Sign in. The session cookie (`__Host-session` over HTTPS, `session` on plain http) is `HttpOnly` and `SameSite=Strict`;
    in Network the sign-in response body is only `expiresAt` and `roles`, with no token. The anonymous cart is merged and
    the merge result is shown (quantities summed up to stock); the cart cookie is deleted.

**Checkout.**
11. Open checkout, enter an address (or pick a saved one) and pick the named option for `tok_sim_approve_4242`. No
    card number is typed anywhere. Confirm: the confirmation page shows order number, lines, amounts, order status and
    payment status, and the cart is empty.
12. Price-change refusal: add a product, then as the operator change its price through the API (use the session of
    section 5, or a bearer token from `POST /api/v1/identity/sessions` as in feature 004 quickstart step 3):
    `PUT /api/v1/catalog/products/{id}` with a new price. Confirm in the storefront: checkout is refused listing the
    changed lines with old and new price. Accept the new prices explicitly, resubmit: the order is placed.
13. Declined: check out with `tok_sim_decline_0001`: the page explains the payment was refused and the order cancelled,
    and the cart is intact for another method.
14. Pending: check out with `tok_sim_unreachable`: the order shows "awaiting payment" with a countdown to the payment
    deadline; the page refreshes by itself (polling every 5 seconds) when you change the status, no reload needed.
15. Double submission: double-click "Confirm", or confirm, go back in the browser and confirm again. Exactly one order
    exists (check the orders list) and the storefront shows that single order.
16. Session expiry during checkout: leave checkout open for 30 minutes (or start the gateway with the idle property
    lowered, see `BrowserSessionProperties` in `docs/gateway.md`, to a minute for the check), then confirm. You are asked
    to sign in and return to the same checkout with address and method kept.

**Account (US4).**
17. Orders list shows only your orders, newest first, with both statuses; open one for lines, amounts, address and
    status history. Cancel a `placed` order after the confirmation prompt; the cancel action disappears for orders that
    may no longer be cancelled.
18. Change an address and a notification preference; each change is confirmed on screen.
19. Sign out, use "forgot password" (same answer for known and unknown emails), open the single-use link from Mailpit
    (a second use fails), set a new password and sign in.
20. Delete the account with your password: you are signed out, cannot sign in again, and the orders remain for the
    operator without personal details.

Also check: a 360 px wide viewport keeps every step usable; a product name containing `<img src=x onerror=alert(1)>`
(created through the API) is shown as text; 11 wrong sign-ins in a minute show "retry in N seconds" and no automatic retries.

## 5. Operator console (US6)

1. Sign in at `/sign-in` as `operator@ecommerce.example` / `Operator-Passw0rd!2026` and open `/console/orders`.
2. The list shows every shopper's orders with both statuses and filters by status. Open one: lines, amounts, address and
   status history.
3. Advance an order `placed` to `preparing` to `shipped`: only allowed transitions are offered, each asks for
   confirmation; the shopper's order page then shows the new status. Cancel a `placed` order after confirming.
4. Adjust the stock (with a reason) of a sold-out product: validation errors appear next to the field; the product is
   addable to a cart again.
5. Open `/console` with a shopper account: the platform answers 403 and no console data is displayed (confirm in
   Network that the data requests themselves were refused, not just hidden); console entry points are absent from the
   shopper's navigation.
6. The console has no create, edit, withdraw or reinstate for products or categories and says that catalogue editing is
   done through the API for now.

## 6. Observability (SC-004, SC-011)

1. In Grafana open **Dashboards, E-commerce platform, Storefront RUM** after a few minutes of browsing. Page-load
   (content) p95 should read under 2 s and action p95 (add to cart, search, sign in) under 1 s on the local stack
   (SC-004).
2. Copy the `X-Correlation-Id` of one request from DevTools (Network, request headers) or from the dashboard, open
   **Requests by correlation id**, enter it: gateway and service lines appear, and **Open trace in Tempo** shows one
   trace joining the browser span (`service.name=storefront`) with the gateway and downstream services (SC-011).
3. Privacy scan after a full journey: in Explore with Loki run `{service="storefront"}` and search the lines and
   attributes for the email you registered, the address you entered and the search term you used, for example
   `{service="storefront"} |~ "(?i)<email>|<street>|<term>"`; expect no results. Telemetry carries only route templates
   (`/products/:id`), timings, interaction kinds and a random session id (SC-011, FR-032).
4. Resilience of telemetry: `docker compose --profile observability stop` (from `platform/compose`), keep browsing and
   checking out. The storefront keeps working; telemetry requests answer 503 at the gateway and are dropped silently,
   with no retry loop and no error shown. Start the profile again and telemetry resumes.

## 7. Accessibility (SC-006)

```bash
npm --prefix frontend ci --silent
STOREFRONT_URL=http://localhost:${GATEWAY_PORT:-8080} npm --prefix frontend run acceptance
```

The suite runs the behaviour-level features against the running stack, including axe checks on the core shopper pages
and a keyboard-only checkout scenario. Expect all scenarios green and 0 critical and 0 serious violations. Without
`STOREFRONT_URL` the suite is skipped, as the JVM acceptance suite is without `GATEWAY_URL`.

## 8. Quality gate and contracts (SC-007)

```bash
./gradlew -q verify                          # silent on success; includes frontend lint and test, Stryker, Pitest, detekt, ktlint
./gradlew -q contractTest contractVerify     # storefront pacts verified by identity, catalog, cart, order, payment and gateway
scripts/tests/run-all.sh                     # bootstrap tests, offline: stubbed docker/podman/java/node/brew, no network or engine
```

Expect no output from the first two. `contractTest` writes the storefront pacts to `build/pacts` (one per provider, listed
in [contracts/pact-matrix.md](./contracts/pact-matrix.md)); `contractVerify` runs the provider classes. To see the gate
fail correctly, change a field the storefront consumes in one provider (for example rename a catalog price field):
`contractVerify` must fail naming the contract and the consumer. `run-all.sh` covers checks, decisions, dry runs and
idempotency of every subcommand (SC-010) and runs `gitleaks` over a recorded `init` transcript (SC-008).

## 9. Maintenance commands (US5)

| Command | Expected |
|---------|----------|
| `scripts/dev-env.sh status` | table of components with `healthy`, `starting`, `unhealthy` or `stopped`, the public addresses, engine memory/CPU/disk and the same prerequisite checks. Stop one service and see it reported. |
| `scripts/dev-env.sh update` | after a pull, rebuilds and restarts only changed components (Compose build cache), keeps data, runs the checks |
| `scripts/dev-env.sh reset` | warns about containers that are not the platform's (never touched), asks to confirm the loss of local data (the prompt names the volumes), stops, removes data, rebuilds what changed, restarts, reseeds, checks. Declining or running without a terminal changes nothing; `--yes` consents non-interactively |
| `scripts/dev-env.sh down` | stops everything, prints that data was kept |
| `scripts/dev-env.sh down --volumes` | same, prints that data was removed |

Every one accepts `--dry-run` (prints, changes nothing). Exit codes: 0 ok, 2 usage, 3 prerequisites missing,
4 platform failed to start or to pass checks ([contracts/dev-env-cli.md](./contracts/dev-env-cli.md)).

## 10. Runner host (FR-033)

```bash
scripts/dev-env.sh init --start --runner-host
```

Does everything of section 2 and also starts the `ci` profile (Pact Broker, http://localhost:9292) and the private image
registry of `platform/ci-runner`, health-checks them, prints their addresses and ends with the pointer to
`platform/ci-runner/README.md`, section "Register the runner". It never asks for, reads or stores a registration token and
never registers the runner. Without the flag, none of this runs.

## 11. Cold start measurement (SC-009)

On a machine with warm base images but no project images, from `platform/compose`:

```bash
docker compose --profile core --profile observability down -v
docker builder prune -af                      # or the podman equivalent: a truly cold build cache
time docker compose --profile core --profile observability up -d --build   # COMPOSE_PARALLEL_LIMIT=1 from .env
docker compose ps                             # every service healthy, storefront included
```

Expect under 5 minutes in total, compared with the 3 min 58 s recorded in feature 004 (SC-006 there; see
`platform/docker/README.md`). The storefront image adds the Node build stage (about 1 minute) and must be the only
growth; record the new figure in `platform/docker/README.md`.

## 12. Troubleshooting

- **Port 8080 taken.** `init --start` detects it before starting, proposes a free port, records `GATEWAY_PORT` in `.env`
  and prints every address with it; verification and reset links in emails follow the port.
- **Nothing becomes healthy on Podman.** `BUILDAH_FORMAT=docker` was not set (the script sets it; rebuild with
  `reset` or `update`). Also check the engine memory (`podman machine set --cpus 6 --memory 10240`) and, when the 20th
  container fails to start, the kernel keyring quota (`kernel.keys.maxkeys`); `check` reports both.
- **Engine too small.** `check` reports memory, CPUs and free disk against 10 GiB, 4 CPUs and 15 GiB; an 8 GiB engine ran
  out of memory in feature 004.
- **Storefront blank, CSP errors on API calls in the console.** The storefront catch-all route must be evaluated after
  every `/api/**` route and the storefront policy applies only to it; the API keeps `default-src 'none'`
  ([contracts/gateway-routes.md](./contracts/gateway-routes.md), research §6). Check route order in the gateway.
- **Sign-in works but the session is lost on plain http.** Cookies whose names start with `__Host-` are only accepted by
  browsers when they carry the `Secure` attribute, which requires HTTPS. Research §2 names the cookies `__Host-session`
  and `__Host-cart` and says `Secure` applies when the request was HTTPS; taken literally, a `__Host-` name without
  `Secure` over `http://localhost` would be rejected by the browser and the session or cart would silently vanish.
  The rule to implement and test is therefore: when the request arrived over HTTPS the gateway names the cookies
  `__Host-session` and `__Host-cart` with `Secure`, `Path=/` and no `Domain`; on plain-http local development it names
  them `session` and `cart` with identical attributes minus `Secure` (`HttpOnly`, `SameSite`, `Path=/`, same sealed
  value, same lifetimes), and the gateway reads whichever name the request carries. This detail is recorded here for the
  planner to mirror in [contracts/openapi/gateway-browser-session.yaml](./contracts/openapi/gateway-browser-session.yaml)
  and [data-model.md](./data-model.md); sections 4 and 5 above use the plain-http names locally.
- **Telemetry missing in Grafana.** The `observability` profile is down (the route answers 503 by design) or the
  collector has no data yet; lines can arrive a few seconds after the request.
- **`init` says "could not verify, no network".** The check needs a download; it is not a missing tool. Rerun online.

## Expected outcomes

| Criterion | Where to observe it |
|-----------|---------------------|
| SC-001 fresh clone to running platform under 30 min | Section 2, `time` of `init --start` |
| SC-002 missing tools in under 30 s; rerun under 10 s | Sections 1 and 2 |
| SC-003 full shopper journey under 5 min | Section 4 timed |
| SC-004 p95 content under 2 s, actions under 1 s | Section 6 step 1, Storefront RUM dashboard |
| SC-005 feature 004 scenarios reproducible through the storefront and console | Sections 4 and 5; acceptance features `@us1` to `@us6` |
| SC-006 zero critical/serious a11y violations, keyboard-only journey | Section 7 |
| SC-007 every storefront edge has a verified contract | Section 8 |
| SC-008 secrets absent from output and tracked files | Section 2 table, `run-all.sh` |
| SC-009 cold start under 5 min with the storefront | Section 11 |
| SC-010 idempotent, dry-runnable commands | Sections 2, 8 and 9 |
| SC-011 correlation id joins browser, gateway and services; zero personal data in telemetry | Section 6 steps 2 and 3 |
