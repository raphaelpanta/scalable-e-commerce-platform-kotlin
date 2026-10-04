# Data Model: Web Storefront and Local Development Bootstrap

**Feature**: 005-storefront-dev-bootstrap | **Inputs**: [spec.md](spec.md), [research.md](research.md), [plan.md](plan.md);
platform field names from `specs/004-ecommerce-platform-mvp/contracts/openapi/{cart,order,identity,catalog}.yaml`

Scope: logical model only. This feature adds **no datastore**. Four groups of models exist: browser-side value
objects (`frontend/src/domain/`), browser-side application models (`frontend/src/app/`), the gateway's sealed browser
session (`services/gateway/.../browser/`) and the bootstrap's records (Bash, described as records). Types are logical
(TypeScript or Kotlin names where useful). The platform's own models stay as feature 004 defined them; the storefront
holds only views of them, and its types for platform responses are generated from the OpenAPI contracts (never
hand-written, constitution VII).

## 1. Conventions

| Topic | Rule |
|-------|------|
| Layering | `domain/` is pure TypeScript with no imports from `app/`, `api/`, `ui/` or browser globals; `app/` imports `domain/` only; `api/` and `ui/` are the edges (ESLint import boundaries enforce this, plan Constitution Check II). |
| Validation | Value objects validate at construction and return a `Result` (`ok(value)` or `err(reason)`); an invalid value cannot exist. Messages for the shopper come from the server's `Problem.errors[]` whenever the server refused; client pre-checks only prevent obviously wasted round trips. The server stays the authority. |
| Testing | Every value object has fast-check property tests (round trip, rejection of the invalid set, idempotence of normalisation) and is inside the Stryker scope (`domain/`, `session/`, `telemetry/`, 80 % threshold). |
| Identifiers | Platform UUIDs stay plain strings validated for UUID shape at the `api/` edge; the storefront never mints business ids, only `CorrelationId`, `IdempotencyKey` and the telemetry session id. |
| Time | ISO-8601 UTC strings from the platform parsed to `Date` at the edge; durations in whole milliseconds. |
| Personal data | Nothing in sections 3.x or 4 below holds a password, token or card data. Email and addresses exist only in component state while a form is open (and in server responses shown to their owner). |

## 2. Browser-side value objects (`frontend/src/domain/`)

| Value object | Fields (type) | Invariants / validation | Notes |
|--------------|---------------|-------------------------|-------|
| `Email` | `value: string` | Trimmed; lower-cased for comparison only (display keeps input); 1 to 254 characters; exactly one `@` with non-empty local part and a domain containing a dot and no whitespace. Deliberately loose: the server decides deliverability. | Contract: `format: email`, `maxLength: 254`. Never logged, never in telemetry. |
| `Password` | `value: string` (opaque, never rendered back) | Policy is **as identity's contract defines**; the storefront pre-checks only **length >= 12** (identity also bounds the maximum at 128 and refuses a password equal to the email) and otherwise shows the server's message. No composition rules are invented client-side. Never trimmed, never persisted, `toString` redacted. | Used by register, sign-in (no length pre-check on sign-in), reset completion and account deletion. |
| `Quantity` | `value: int` | Integer; 1 <= value <= 99 (the platform's per-line maximum, feature 004 data model); `Quantity.zero` exists only as the "remove line" command (`UpdateLineRequest.quantity` 0). Merge results may report a smaller applied quantity (`CappedLine.appliedQuantity`). | The UI additionally caps at the stock shown when the catalog reports `availableQuantity` (operators only), otherwise the server's refusal is displayed. |
| `Money` | `amountMinor: int (non-negative, safe integer)`, `currency: string (^[A-Z]{3}$)` | Minor units plus ISO 4217 code exactly as the platform sends them. Only operation: `format(locale)` through `Intl.NumberFormat` using the currency's minor-unit digits. Comparison for equality is allowed (flags price change display); **no addition, multiplication or rounding**: line totals, cart total and order total always come from the server. | Single currency (BRL) in seed data, but the type does not assume it. |
| `Address` | `recipientName` (1-100), `line1` (1-150), `line2?` (<= 150), `city` (1-100), `region?` (<= 100), `postalCode` (1-20), `countryCode` (`^[A-Z]{2}$`), `label?` (<= 50), `isDefault` (bool, default false) | Mirrors identity's `AddressInput` bounds; trimmed; no postal-code format rules per country (server decides). `AddressRef` = `{id: uuid}` for a saved address. | Order responses snapshot the address as `DeliveryAddress` (`country` instead of `countryCode`); the `api/` edge maps the name. |
| `CartRevision` | `value: string` (opaque, non-empty) | Never parsed or compared for order, only for equality; sent as `cartRevision` on place-order. | Changes whenever lines, quantities or prices change (cart contract). |
| `OrderStatus` | enum: `placed`, `preparing`, `shipped`, `delivered`, `cancelled` | Allowed transitions copied from feature 004 (table 2.1). `allowedNext(status)` is a pure function used only to decide which buttons to offer; the platform still validates. | Terminal: `delivered`, `cancelled`. |
| `PaymentStatus` | enum: `pending`, `approved`, `failed` | Transitions: `pending -> approved`, `pending -> failed`; `approved` and `failed` terminal. | Independent of `OrderStatus`; both are always shown (FR-009). |
| `RouteTemplate` | `value: string` | One of the closed list in table 2.2 (or `/unknown` for unmatched paths). Built by matching the current pathname against the router's route list, never by regex-stripping a URL, so no id, token or query can survive. | The only form in which a navigation target reaches telemetry. |
| `CorrelationId` | `value: string` (UUID v4) | Canonical lower-case UUID; created per page view and renewed per user action; sent as `X-Correlation-Id` on every request and attached to every telemetry event of that action. | Not a credential and not personal data. |
| `IdempotencyKey` | `value: string` (UUID v4) | Canonical lower-case UUID; sent as `Idempotency-Key` on place-order only. Generated once per distinct request body (see 3.2). | Persisted in the checkout draft (sessionStorage), so a reload does not lose it. |

### 2.1 Order and payment transitions (copied from feature 004)

| Status kind | From | To | Who may trigger | Guard |
|-------------|------|----|-----------------|-------|
| order | (none) | placed | shopper (checkout) | cart revision current, stock reserved; paymentStatus starts `pending` |
| order | placed | preparing | operator | paymentStatus is `approved` |
| order | preparing | shipped | operator | none |
| order | shipped | delivered | operator | none |
| order | placed | cancelled | shopper, operator, system | reasons `SHOPPER_REQUEST`, `OPERATOR`, `PAYMENT_FAILED`, `PAYMENT_EXPIRED` |
| order | preparing | cancelled | operator (and shopper, see Open points) | reason `OPERATOR` |
| order | shipped, delivered, cancelled | anything | nobody | terminal, refused |
| payment | pending | approved | system | provider approval |
| payment | pending | failed | system | provider decline, or still pending after 30 minutes (order becomes `cancelled`) |

### 2.2 Route templates (closed list, shared with `contracts/storefront-routes.md`)

`/`, `/categories/:id`, `/search`, `/products/:id`, `/cart`, `/sign-in`, `/register`, `/verify-email`, `/reset-password`,
`/forgot-password`, `/checkout`, `/orders`, `/orders/:id`, `/orders/:id/confirmation`, `/account`, `/account/addresses`,
`/account/notifications`, `/console/orders`, `/console/orders/:id`, `/console/stock`, `/unknown` (the same list as
`frontend/src/domain/routeTemplate.ts` `ROUTE_TEMPLATES`). Query strings (page, search term, token) are never part of a template.

## 3. Application models (`frontend/src/app/`)

### 3.1 SessionSummary (and the session state machine)

| Field | Type | Rules |
|-------|------|-------|
| `state` | `anonymous` \| `signedIn` | Initial value on page load is resolved by `GET /api/v1/identity/accounts/me` through the cookie: 200 -> `signedIn`, 401 -> `anonymous` (plan, session summary decision). |
| `expiresAt` | `Date?` | Present only when `signedIn`; the value the gateway returned with the sign-in or refresh response. A **hint** for showing "session about to end"; it never grants anything and the truth is always the next response (a 401 wins). |
| `roles` | `('shopper' \| 'operator')[]` | Empty when `anonymous`. Used only to show or hide console entry points (FR-012); authorisation is decided by the services. |

The summary holds no token, no password, no account id and no email (the email shown on the account page comes from
`GET /accounts/me` into component state, not into the summary).

| From | To | Trigger | Effects |
|------|----|---------|---------|
| anonymous | signedIn | sign-in 200 (`{expiresAt, roles}`), then cart merge | merge result shown; `__Host-cart` deleted by the gateway after merge |
| signedIn | anonymous | explicit sign-out (`DELETE /sessions/current`) | cookie deleted by the gateway; query cache cleared |
| signedIn | anonymous | any 401 from a protected call (session gone, idle expiry, revoked) | cache cleared; the user is sent to `/sign-in` with a same-origin return route (open-redirect safe: only a template from 2.2 is accepted) |
| signedIn | anonymous | browser closed | the browser drops the cookie (no `Max-Age`); next load resolves to anonymous |
| signedIn | signedIn | any API call while active | gateway re-sets the cookie with a new `lastSeenAt` (silent renewal); `expiresAt` updated when the response carries it |

There is no "keep me signed in" state (clarification 2026-10-04).

### 3.2 CartView and CheckoutDraft

**CartView** (derived from the cart contract's `Cart`; read-only):

| Field | Type | Source / rule |
|-------|------|---------------|
| `id` | uuid | `Cart.id` |
| `revision` | `CartRevision` | `Cart.revision` |
| `lines[]` | list of `CartLineView` | `Cart.lines` |
| `lines[].id`, `productId`, `productName` | uuid, uuid, string | rendered as text only (FR-015) |
| `lines[].quantity` | `Quantity` | |
| `lines[].priceAtAdd`, `currentPrice`, `lineTotal` | `Money` | totals from the server |
| `lines[].priceChanged` | bool | `CartLine.priceChanged` (current price differs from price at add) |
| `lines[].unavailable` | bool | derived: product withdrawn or `availability.inStock` false from the catalog lookup, or reported by a refused checkout (`unavailableLines`); such a line is excluded from checkout until removed and the shopper is told why (edge case "stale cart") |
| `total` | `Money` | `Cart.total` |
| `updatedAt` | Date | `Cart.updatedAt` |
| `mergeNotice?` | list of `{productId, requestedQuantity, appliedQuantity}` | from `MergeResult.cappedLines`, shown once after sign-in |

Invariant: `lines` is empty exactly when the cart is shown as empty; the UI never computes a total.

**CheckoutDraft** (persisted in `sessionStorage` under one key; ids and the key only, **no personal data**):

| Field | Type | Rules |
|-------|------|-------|
| `addressId` \| `newAddress` | uuid \| `Address` (form state only) | Persisted form: the **id** of a saved address. A new address is first saved through identity (`POST /accounts/me/addresses`) and then referenced by id, so the persisted draft never contains address text. |
| `paymentMethodId` | string | One of the simulated method tokens the platform offers locally (sent as `paymentMethod: {type: card, token}`); never a card number. |
| `acknowledgedRevision` | `CartRevision` | The revision the shopper last saw or explicitly accepted; sent as `cartRevision`. |
| `idempotencyKey` | `IdempotencyKey` | See rule below. |
| `step` | enum | See state machine. |

Idempotency rule (aligned with the order contract): one key per distinct request body. The key is **reused** for any
byte-identical resubmission (lost response, double click, back-and-resubmit, "payment pending, retry"); it is
**regenerated** when the body changes (new `cartRevision` after price acceptance, other address, other payment
method), because the platform answers 422 `idempotency-key-reuse` for the same key with a different body.

| From | To | Trigger |
|------|----|---------|
| reviewing | submitted | shopper confirms; request sent with the draft's key |
| submitted | confirmed | 201 (also an idempotent replay): single order shown, cart empty, draft deleted |
| submitted | refusedPriceChange | 409 `price-changed`: `changedLines` (old and new price per line) and `currentCartRevision` shown |
| refusedPriceChange | acknowledged | shopper explicitly accepts the new prices: `acknowledgedRevision := currentCartRevision`, new `idempotencyKey` |
| acknowledged | submitted | shopper confirms again |
| submitted | refused | 422 insufficient stock (`unavailableLines` named), payment declined (order recorded `cancelled`, `declineReason` explained), or other refusal |
| refused | reviewing | shopper adjusts the cart, picks another method or fixes the field in error; key regenerated if the body will differ |
| submitted | submitted | network error or timeout: automatic **no** retry loop; the shopper retries manually with the same key |
| any | reviewing | 401 while submitting: sign-in prompt returns to `/checkout`, draft restored from `sessionStorage` (US2 scenario 10) |

Terminal states are `confirmed` and (until the shopper acts) `refused`; reaching `confirmed` deletes the stored draft.

### 3.3 OrderView

| Field | Type | Source / rule |
|-------|------|---------------|
| `id` | uuid | `Order.id` |
| `number` | string | the order number shown to the shopper; see Open points (the 004 contract's `Order` carries `id` only, so until the platform exposes `number` the storefront shows a short form of `id`) |
| `lines[]` | `{productId, name, unitPrice: Money, quantity: Quantity, lineTotal: Money}` | `Order.lines` (name is the snapshot at confirmation) |
| `total` | `Money` | `Order.total` |
| `deliveryAddress` | `{recipientName, line1, line2?, city, postalCode, country}` | `Order.deliveryAddress` snapshot; rendered as text |
| `orderStatus` | `OrderStatus` | `Order.orderStatus` |
| `paymentStatus` | `PaymentStatus` | `Order.paymentStatus` |
| `cancellationReason?` | `SHOPPER_REQUEST` \| `OPERATOR` \| `PAYMENT_FAILED` \| `PAYMENT_EXPIRED` | present only when `cancelled` |
| `paymentDeadline?` | `Date` | present only while `paymentStatus = pending`: placement time + 30 minutes (platform rule; see Open points for the contract gap). Drives the "awaiting payment, N minutes left" display; polling every 5 s while `pending`, stopped on a final status or after the deadline. |
| `history[]` | `{kind: order \| payment, status, at, by}` | `Order.statusHistory`, newest last; `by` is shown as "you", "operator" or "system", never as an account id |
| `createdAt` | Date | `Order.createdAt` |
| `allowedActions[]` | subset of `cancel`, `advance(next)` | Pure function of (`orderStatus`, `paymentStatus`, role): `cancel` for a shopper only when `orderStatus` is `placed`, for an operator when it is `placed` or `preparing`; `advance(preparing)` only when `placed` and payment `approved`; `advance(shipped)` when `preparing`; `advance(delivered)` when `shipped`; `advance` is offered to operators only. Empty for terminal statuses. Confirmation dialog before each action (FR-010, FR-011). |

Invariants (checked in property tests against the 004 rules): `cancellationReason` present iff `cancelled`;
`paymentStatus` is `approved` whenever `orderStatus` is `preparing`, `shipped` or `delivered`; `paymentDeadline`
present iff `paymentStatus` is `pending`.

### 3.4 ConsoleOrderFilter and StockAdjustment

| Model | Field | Type | Rules |
|-------|-------|------|-------|
| `ConsoleOrderFilter` | `orderStatus?` | `OrderStatus` | Applied client-side within the loaded page unless the order contract offers the parameter (plan, Console order list). |
| | `paymentStatus?` | `PaymentStatus` | Same. |
| | `page`, `size` | int >= 0, 1..100 | Reflected in the URL like all list state (FR-003). |
| `StockAdjustment` | `productId` | uuid | An existing product only; the console offers no product or category creation, edit, withdrawal or reinstatement (FR-011). |
| | `delta` | non-zero integer | Positive adds, negative removes; the stock value after the change is the server's, a result that would be negative is refused 422 and shown next to the `delta` field. |
| | `reason` | string, 1..255, free text typed by the operator | Sent to the platform; **never** copied into telemetry. |

### 3.5 TelemetryEvent

| Field | Type | Rules |
|-------|------|-------|
| `kind` | `pageView` \| `interaction` \| `error` \| `timing` | |
| `attributes` | map restricted to the allow-list below | Any other key is dropped by the `telemetry/` module before export; the collector's attribute and redaction processors are the second layer (research §4). |
| `at` | timestamp | |

**Allow-list (exactly these attributes):**

| Attribute | Type | Used by kinds |
|-----------|------|---------------|
| route template | `RouteTemplate` (e.g. `/products/:id`) | pageView, interaction, timing, error |
| http method | `GET` \| `POST` \| `PUT` \| `PATCH` \| `DELETE` | timing, error |
| status code | int (100..599) | timing, error |
| element role / id | ARIA role plus the element's static, developer-assigned id (never its text or value) | interaction |
| duration (ms) | int >= 0 | timing |
| error name | class name only (`TypeError`, `ProblemError`); no message, no stack text containing data | error |
| correlation id | `CorrelationId` | all |
| session id | random UUID kept in `sessionStorage`, **never** the session credential | all |

**Forbidden (must never appear in any event, enforced by property tests that fuzz inputs):** url query string, form
values, search terms, free text typed by the shopper or operator, account id, email, and any token or cookie value.
Resource attributes added by the SDK: `service.name=storefront`, `service.version`.

Delivery rules: batches go to `/api/v1/telemetry/v1/{traces,logs}`; a failed or 503 delivery is dropped silently, never
retried in a loop and never blocks a page (FR-032); no `localStorage`.

## 4. Gateway-side model (Kotlin, `services/gateway/.../browser/`)

### 4.1 SealedSession

Pure value type; sealing is an adapter around it. Payload (before sealing):

| Field | Type | Rules |
|-------|------|-------|
| `accessToken` | string | The identity JWT; refreshed when it expires within 60 s. |
| `refreshToken` | string | Opaque; rotates on every refresh through identity. |
| `accountId` | uuid | Used for logging correlation only via hashed form; never logged raw with the cookie. |
| `roles` | set of `shopper` \| `operator` | Echoed in the sign-in response body; authorisation still decided by services. |
| `lastSeenAt` | instant | Updated on every request that used the cookie. |
| `issuedAt` | instant | Set at sign-in; not extended by renewal (an upper bound for audits, not an expiry rule). |

**Sealing rules**

| Rule | Value |
|------|-------|
| Algorithm | AES-256-GCM, key from `BROWSER_SESSION_KEY` (32 bytes, Base64) |
| Nonce | 12 random bytes per sealing (a new nonce on every re-set, never reused with the same key) |
| Key identifier | short prefix (`k1`, `k2`, ...) so the key can rotate: unseal picks the key by id; an unknown id means unseal failure; the id is also bound as AEAD associated data |
| Wire format | `<keyId>.<Base64url(nonce || ciphertext || tag)>`, no padding |
| Payload encoding | compact JSON (UTF-8) of the table above |
| Size budget | whole cookie (name, value, attributes) **<= 4 KiB**; sealing fails closed (sign-in answers an error, no truncated cookie) when the budget would be exceeded |
| Logging | the cookie value, its plaintext and both tokens are never logged |
| Cookie name | `__Host-session` on HTTPS, `session` on plain HTTP; see 4.2 |
| Sharing | every gateway replica holds the same key set, so the gateway stays stateless |

**Validity rules**

- Unseal failure (bad tag, unknown key id, malformed value) -> treated as no session: 401 `unauthorized` and the cookie deleted.
- `lastSeenAt` older than **30 minutes** -> 401 `unauthorized` and the cookie deleted (idle expiry).
- Access token expiring within **60 seconds** -> refresh through identity first; the rotated tokens replace the payload. A refused refresh -> 401 and the cookie deleted.
- Every response that used the cookie re-sets it with a new `lastSeenAt` (silent renewal while active, FR-014).

### 4.2 Cookies

Naming rule: `__Host-` prefixed cookies require `Secure` and HTTPS, so the gateway uses the prefixed name (with `Secure`) when the request arrived over HTTPS and the unprefixed name (same attributes minus `Secure`) over plain HTTP such as `http://localhost`. On input the gateway accepts whichever name is present, **never both**: a request carrying both the prefixed and the plain name of the same cookie answers 401 `unauthorized` with both cookies deleted (no cookie used), as the gateway contract defines. Below, "`__Host-session`" and "`__Host-cart`" in other sections mean "the session / cart cookie under whichever name applies".

| Attribute | session cookie | cart cookie |
|-----------|----------------|-------------|
| Name | `__Host-session` over HTTPS; `session` over plain HTTP (for example `http://localhost`) | `__Host-cart` over HTTPS; `cart` over plain HTTP |
| Content | sealed session payload | sealed anonymous cart token (the `X-Cart-Token` value) |
| `HttpOnly` | yes | yes |
| `Secure` | yes when the name is `__Host-session` (HTTPS); omitted for the plain-HTTP name `session` | yes for `__Host-cart`; omitted for `cart` |
| `SameSite` | `Strict` | `Lax` |
| `Path` | `/` | `/` |
| `Domain` | never set (required by the `__Host-` prefix, kept for the plain names too) | never set |
| Lifetime | no `Max-Age`/`Expires`: ends with the browser; plus the 30-minute idle rule above | `Max-Age` 30 days (2,592,000 s) |
| Set when | sign-in or refresh with `X-Browser-Session: cookie` answers 200; every cookie-authenticated response (renewal) | a cart response carries `X-Cart-Token` and the request carried `X-Browser-Session: cookie` |
| Deleted when | sign-out, unseal failure, idle expiry, refused refresh | after `mergeCart` succeeds |
| Response header | the `X-Cart-Token` header is removed from browser responses | |

### 4.3 Request decision table

Evaluated in order for requests to `/api/**`; the first matching row wins. "Cookie" means the session cookie (`__Host-session` or `session`, exactly one of them) present;
"Fetch-site/Origin OK" means `Sec-Fetch-Site` is `same-origin` or `none` **and** `Origin` (when present) matches the
request host.

| # | Cookie? | `Authorization`? | Method | Fetch-site / Origin | Result |
|---|---------|------------------|--------|---------------------|--------|
| 1 | no | no | any | n/a | **pass through** unchanged (anonymous; services decide) |
| 2 | no | yes | any | n/a | **pass through** unchanged (API client keeps bearer tokens, behaviour as before) |
| 3 | yes | yes | any | n/a | **400** `validation` (cookie and own `Authorization` are mutually exclusive) |
| 4 | yes | no | non-GET (anything except GET, HEAD, OPTIONS) | not OK (cross-site, same-site, absent `Sec-Fetch-Site`, or `Origin` mismatch) | **403** `forbidden`, cookie untouched |
| 5 | yes | no | any | OK, or method is GET/HEAD/OPTIONS | unseal and check idle: failure or idle -> **401** `unauthorized` plus cookie deletion |
| 6 | yes | no | any | OK, session valid | refresh first if the access token expires within 60 s; then **inject** `Authorization: Bearer <access>`, forward, re-set the cookie with a new `lastSeenAt` |

Other rules: the cookie is ignored for non-API routes (storefront catch-all); `X-Browser-Session: cookie` on sign-in
or refresh converts the token body to `{expiresAt, roles}` (identity's contract is unchanged); the telemetry routes
strip cookies and `Authorization` before forwarding to the collector.

## 5. Bootstrap models (Bash, described as records)

### 5.1 EnvironmentCheck

| Field | Type | Rules |
|-------|------|-------|
| `id` | string (stable, e.g. `jdk`, `engine-memory`) | Unique; used by tests, `--install` mapping and output. |
| `name` | string | Human label. |
| `found` | string | Observed value, or `n/a`. Never contains a secret. |
| `expected` | string | Requirement text. |
| `result` | `PASS` \| `FAIL` \| `SKIP` | `SKIP` means "could not verify, no network" (never reported as `FAIL`). |
| `remediation` | map os -> text | Keys `macos`, `debian`, `fedora`; one exact command or step per OS; printed for every `FAIL`. |
| `fixable` | bool | True when `--install` (package manager) or consented local configuration can fix it; false for engine resources and a stopped engine (guidance only). |

A check run changes nothing. Result aggregation: any `FAIL` -> exit 3 for prerequisites, `SKIP` never fails.

### 5.2 The checks (research §8)

| # | id | Expected | Remediation basis | Fixable |
|---|----|----------|-------------------|---------|
| 1 | `jdk` | JDK matching `.java-version` | SDKMAN `sdk env install` (`.sdkmanrc`) on all OSes | yes (opt-in) |
| 2 | `engine-reachable` | container engine answers (Docker or Podman; Podman behind a Docker-compatible socket detected as Podman) | start the engine / install Docker Desktop or Podman (choice printed, not made) | no |
| 3 | `compose-v2` | Compose v2 provider available | `docker-compose-plugin` (apt/dnf), bundled with Docker Desktop, `podman-compose` | yes (opt-in, Linux) |
| 4 | `engine-memory` | >= 10 GiB | raise the VM/machine memory | no |
| 5 | `engine-cpus` | >= 4 | raise VM/machine CPUs | no |
| 6 | `engine-disk` | >= 15 GiB free | prune unused images or enlarge the disk | no |
| 7 | `podman-quirks` (SKIP unless Podman) | `BUILDAH_FORMAT=docker` set; `ryuk.disabled=true` in `~/.testcontainers.properties`; keyring-quota and socket-shim notes shown | write `.env` hint and testcontainers property with consent | yes (consent) |
| 8 | `node` | Node 24 (`frontend/.nvmrc`) | `brew install node@24`, distro package or `fnm` | yes (opt-in) |
| 9 | `npm` | npm shipped with Node 24 | comes with Node | with #8 |
| 10 | `gitleaks` | present | `brew install gitleaks`; release binary or package on Linux | yes (opt-in) |
| 11 | `curl` | present | package manager | yes (opt-in) |
| 12 | `jq` | present | package manager | yes (opt-in) |
| 13 | `openssl` | present | package manager | yes (opt-in) |
| 14 | `gateway-port` | `GATEWAY_PORT` (default 8080) free; a free port proposed otherwise | record the proposal in `.env` | yes (consent) |
| 15 | `hooks` | `git config core.hooksPath` equals `.githooks` | `git config core.hooksPath .githooks` | yes (inside the repository, no consent needed beyond dry-run) |

Runner-host mode adds `ci-services` (registry and Pact Broker started and healthy); it never checks or reads a
runner registration token.

### 5.3 LocalEnvironmentConfiguration (`platform/compose/.env`, git-ignored)

| Key | Managed value | Rule |
|-----|---------------|------|
| `IDENTITY_SIGNING_KEY` | generated random secret (Base64) when empty | Never printed, never committed. |
| `BROWSER_SESSION_KEY` | generated 32 random bytes, Base64, when empty | Same; also the first key id (`k1`) of the gateway key set. |
| `GATEWAY_PORT` | default 8080, or the free port the developer accepted | Recorded so every printed address and the notification links use it. |
| `COMPOSE_PARALLEL_LIMIT` | 1 | Images build one at a time (plan, Performance Goals). |
| `BUILDAH_FORMAT` hint | `docker` (Podman only) | Written to `.env` and/or printed as an export line; with consent for anything outside the repository. |

Rules: created from `.env.example` when missing; **a non-empty value is never overwritten**; partial writes are
atomic (temp file then move) so an interrupted run never leaves an unreadable file; secrets never appear in output,
logs or tracked files (SC-008); `--dry-run` prints keys that would change, never values.

### 5.4 PlatformComponentStatus

| Field | Type | Derivation from `docker compose ps --format json` |
|-------|------|---------------------------------------------------|
| `name` | string | `Service` |
| `state` | `healthy` \| `starting` \| `unhealthy` \| `stopped` | `Health = healthy` -> healthy; `Health = starting`, or `State = running` with a health check not yet reporting, or `State = restarting` -> starting; `Health = unhealthy` -> unhealthy; `State` in {exited, dead, created, paused} or the service absent from the output -> stopped; running with no health check defined counts as healthy |
| `address` | string? | Only the gateway publishes a port: `http://localhost:<GATEWAY_PORT>` (storefront and API), plus mail inbox and dashboards addresses behind the observability profile; services on the `internal` network have none |

Scope: only containers belonging to the platform's Compose project (project label filter); containers outside it are
never listed as components and never stopped or removed (FR-028). Aggregate: `healthy` for all -> OK; any `unhealthy`
or `stopped` -> failure (exit 4 for `init --start`, `update`, `reset`).

### 5.5 Exit codes

| Code | Meaning | Used by |
|------|---------|---------|
| 0 | success; nothing failed (including "nothing to do") | all subcommands |
| 2 | usage error (unknown subcommand or flag, conflicting flags) | all |
| 3 | prerequisites missing (at least one `FAIL`); nothing was changed | `init`, `check`, `status`, others when run first |
| 4 | platform failed to start or failed its smoke checks (components not healthy, gateway not answering, a service port exposed, storefront not loading) | `init --start`, `update`, `reset` |

Flags that affect the model: `--install` (opt-in installs, each announced), `--yes` (consent for data loss),
`--dry-run` (prints every change, makes none, checks still run), `--runner-host`, `--verbose`. Non-interactive runs
take the safe default (no install, no reset) and report the choice.

## 6. Relationships

- `SessionSummary.roles` gates console entry points; the services still authorise (FR-012).
- `CartView.revision` -> `CheckoutDraft.acknowledgedRevision` -> place-order `cartRevision`; the server's `currentCartRevision` replaces it only after explicit acceptance.
- `CheckoutDraft.idempotencyKey` -> `Idempotency-Key` header; a confirmed draft yields exactly one `OrderView`.
- `OrderView.allowedActions` derives from `OrderStatus`, `PaymentStatus` and the role from `SessionSummary`.
- `TelemetryEvent.correlation id` = the `CorrelationId` sent as `X-Correlation-Id` on the request the event relates to, which joins the gateway access line and every service log line (SC-011).
- `SealedSession` is created at sign-in (identity `TokenPair` -> sealed payload) and is the only holder of tokens; `SessionSummary` is its tokenless projection in the browser.
- `EnvironmentCheck` results feed `status`, `init` and `check`; `LocalEnvironmentConfiguration` feeds Compose; `PlatformComponentStatus` is derived from the engine, never stored.

## 7. Open points (resolved on 2026-10-04, see plan.md "Design Decisions Resolving Spec Gaps")

Resolutions: 1 → shoppers cancel only while `placed` (spec aligned; `allowedActions.cancel` for a shopper requires `placed`, for an operator `placed` or `preparing`); 2 → additive `paymentExpiresAt` on the order contract, order `id` is the number (shown shortened); 3 → one key per distinct body, as modelled; 4 → cookie names per scheme, as modelled; 5 → `expiresAt` = end of the idle window; 6 → fail closed, as modelled; 7 → as modelled.

Original findings, kept for traceability:

1. **Shopper cancel while `preparing`.** Spec US4 scenario 2 lets a shopper cancel an order in `placed` or `preparing`; feature 004's order contract allows shoppers to cancel only while `placed` (operators: `placed` or `preparing`). This model follows the spec in `allowedActions` for `cancel`, but the platform will refuse the shopper's cancel in `preparing` and the UI must show that refusal. Recommended: amend the spec (shopper: `placed` only), or extend the platform in a later feature.
2. **Order number and payment deadline are not in the 004 `Order` schema.** The contract exposes `id` and `createdAt` only; the 004 data model has `number` and `paymentExpiresAt`. Until the platform exposes them (gap to record for the platform, per the spec's assumptions), the storefront shows a short form of `id` and computes `paymentDeadline` as `createdAt` + 30 minutes while `paymentStatus = pending`. Plan's wording "the order's payment deadline field" assumes the platform field.
3. **"Single idempotency identity" vs the order contract.** Spec FR-008 and the key entity describe one identity for every resubmission; the contract answers 422 for the same key with a different body and instructs a new key after price acceptance. This model keeps one key per distinct request body (reused for identical retries).
4. **`__Host-` prefix on plain HTTP (resolved by coordinator amendment).** Research §2-§3 names the cookies `__Host-session` / `__Host-cart` and sets `Secure` only on HTTPS, but browsers reject a `__Host-` cookie without `Secure`. Amended here: prefixed names with `Secure` over HTTPS, unprefixed names (`session`, `cart`) without `Secure` over plain HTTP; either name accepted on input, never both. research.md §2-§3, plan.md and the gateway contract still say `__Host-*` only and should be aligned.
5. **Meaning of `expiresAt`.** Research gives the body `{expiresAt, roles}` without defining the instant (access-token expiry or end of the idle window). This model treats it as a hint only.
6. **Absent `Sec-Fetch-Site`.** Research requires `same-origin` or `none` for non-GET requests with the cookie; this model fails closed (403) when the header is absent. Very old browsers are therefore unsupported for state-changing calls.
7. **`allowedActions` for `advance(preparing)`** depends on `paymentStatus = approved` (004 guard); the console hides the action otherwise and still displays the platform's refusal if state moved meanwhile.
