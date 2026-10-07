# Consumer-Driven Contract (Pact) Matrix: Storefront

Feature: 005-storefront-dev-bootstrap. Covers FR-018 and SC-007. Additive to
[`specs/004-ecommerce-platform-mvp/contracts/pact-matrix.md`](../../004-ecommerce-platform-mvp/contracts/pact-matrix.md):
the rules of that file ("How the matrix works", "Rules for contract changes") apply unchanged. This file adds one consumer,
`storefront`, and one new provider relationship, the gateway.

## How the storefront fits the matrix

- **Consumer**: `storefront` (`frontend/pact/`, Pact JS, one test file and one pact per provider:
  `storefront-identity.json`, `storefront-catalog.json`, `storefront-cart.json`, `storefront-order.json`,
  `storefront-payment.json`, `storefront-gateway.json`). Each pact describes only what the storefront uses.
- **Publication**: the storefront writes its pacts to the same root `build/pacts` as the services (so
  `./gradlew -q contractTest contractVerify` verifies them with the services' pacts) and the storefront pipeline
  (`.github/workflows/storefront.yml`) publishes them, with the commit as consumer version, to the same Pact Broker (profile `ci`)
  as the services, followed by the "can I deploy" check.
- **Verification owner**: the provider of each pact, in its own build, against its real adapter layer. A provider change that
  makes a storefront interaction unverifiable fails the provider's pipeline naming the interaction and the `storefront` consumer.
- **Gateway provider verification of the storefront pact is new**: before feature 005 the gateway was only a consumer
  (of identity's JWKS) and had no provider pact. `services/gateway/src/contractTest` gains provider tests tagged `provider` that
  replay `storefront-gateway.json` against the real gateway with WireMock standing in for identity, cart and the collector.
- **What the service pacts cover**: services see the storefront's requests as the gateway forwards them, so the identity, cart and
  order pacts describe the request the services receive in bearer mode (the gateway has already injected `Authorization` and
  `X-Cart-Token`) and the responses the storefront relies on. Cookie behaviour exists only at the gateway and is therefore
  covered only by `storefront-gateway.json`.
- **Provider states** are named in the pact and set up by the provider's own fixtures. Names below are the contract.
- The storefront sends `X-Browser-Session: cookie` and `X-Correlation-Id` on every request; both are tolerated by every provider
  (unknown headers are ignored) and are not part of the matched request except in the gateway pact.

## Storefront to identity (`storefront-identity.json`, verified by identity)

| # | Interaction (`operationId`) | Variants | Provider state | Used by |
| --- | --- | --- | --- | --- |
| I1 | `signIn` | 200 token pair (the gateway turns it into the cookie summary; asserted at the gateway, here only the request shape) | `an account ana@example.com exists with a verified email and password S3cure-passphrase!` | sign-in |
| I2 | `signIn` | 401 `unauthorized` invalid credentials; 403 `forbidden` unverified email; 429 `throttled` with `Retry-After` | `an account ana@example.com exists with an unverified email`, `ana@example.com has 5 failed sign-ins` | sign-in states |
| I3 | `registerAccount` | 202 generic message (same body for a new and an already registered email); 422 password policy with `errors[]` | `no account exists for new@example.com`, `an account ana@example.com exists with a verified email` | register |
| I4 | `verifyEmail` | 204; 400/422 for an invalid, used or expired token | `a pending verification token tok-valid exists`, `the verification token tok-expired is expired` | verify email |
| I5 | `refreshSession` | 200 token pair (request body `refreshToken`; the gateway supplies it from the cookie) | `an account ana@example.com has a valid refresh token` | silent renewal (via gateway) |
| I6 | `signOut` | 204 | `an account ana@example.com is signed in` | sign-out |
| I7 | `getOwnProfile` | 200 `Account` with `roles`, `emailVerified`; 401 | `an account ana@example.com is signed in`, `no valid token` | session probe, account |
| I8 | `updateOwnProfile` | 200; 422 | `an account ana@example.com is signed in` | account |
| I9 | `deleteOwnAccount` | 204; 403 for an operator | `an account ana@example.com is signed in`, `an operator ops@example.com is signed in` | account |
| I10 | `listOwnAddresses` | 200 page with items; 200 empty page | `ana@example.com has 2 addresses`, `ana@example.com has no addresses` | checkout, addresses |
| I11 | `addOwnAddress` | 201 `Address`; 422 per-field errors | `an account ana@example.com is signed in` | checkout, addresses |
| I12 | `updateOwnAddress` | 200; 404 not owned | `ana@example.com has 2 addresses` | addresses |
| I13 | `deleteOwnAddress` | 204; 404 | `ana@example.com has 2 addresses` | addresses |
| I14 | `getOwnNotificationPreferences` | 200 `channels`, `phoneVerified` | `ana@example.com has email notifications enabled` | notifications |
| I15 | `updateOwnNotificationPreferences` | 200; 422 sms without a verified phone | `ana@example.com has email notifications enabled` | notifications |
| I16 | `requestPhoneVerification`, `confirmPhoneVerification` | 202 / 204; 422 wrong code | `ana@example.com has a pending phone verification` | notifications |
| I17 | `requestPasswordReset` | 202 generic message regardless of the email | `an account ana@example.com exists with a verified email`, `no account exists for new@example.com` | forgot password |
| I18 | `completePasswordReset` | 204; 400/422 invalid, used or expired token or weak password | `a pending password reset token tok-reset exists` | reset password |

Token fixtures: the state sentences name a token family (`tok-valid`, `tok-expired`, `tok-reset`, the refresh token
`9b8d6c1a-opaque-refresh-token`); the value the consumer sends, and the provider state seeds by hash, is that name padded
with `0` to the 43 url-safe characters identity accepts as an opaque token (`tok-valid-000000000000000000000000000000000`,
`tok-expired-0000000000000000000000000000000`, `tok-reset-000000000000000000000000000000000`,
`9b8d6c1a-opaque-refresh-token-0000000000000`). The 429 of I2 matches `Retry-After` as digits, not a fixed value.

## Storefront to catalog (`storefront-catalog.json`, verified by catalog)

| # | Interaction (`operationId`) | Variants | Provider state | Used by |
| --- | --- | --- | --- | --- |
| C1 | `listProducts` | 200 page (default paging); 200 with `page`/`size`; 200 empty page | `the catalogue has 25 active products`, `the catalogue has no products` | home, category, search |
| C2 | `listProducts` | with `q` (name search; ranking as returned); with `categoryId` (category and descendants) | `the catalogue has active products matching "shoes"`, `category 5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45 has 3 products` | search, category |
| C3 | `listCategories` | 200 tree/list; 200 empty | `the catalogue has 3 categories`, `the catalogue has no categories` | navigation, category |
| C4 | `getCategory` | 200; 404 for a missing or withdrawn category | `category 5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45 exists`, `category 00000000-0000-4000-8000-000000000000 does not exist` | category page title |
| C5 | `getProduct` | 200 in stock; 200 out of stock (not addable); 404 withdrawn or unknown | `product 0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21 is active with stock 5`, `... is active with stock 0`, `... is withdrawn`, `product 00000000-0000-4000-8000-000000000000 does not exist` | product page |
| C6 | `adjustStock` (console, operator) | 201 adjustment applied; 404 unknown product; 422 per-field error (reason required, negative result); 403 shopper | `product 0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21 is active with stock 5` with `an operator ops@example.com is signed in`, `a shopper ana@example.com is signed in` | console stock |
| C7 | `listProducts` with `includeWithdrawn=true` (console, operator) | 200 including withdrawn products | `the catalogue has 25 active products and 2 withdrawn` | console stock |

## Storefront to cart (`storefront-cart.json`, verified by cart)

The cart service sees `X-Cart-Token` (injected by the gateway from the cart cookie) or a bearer; it never sees a cookie.

| # | Interaction (`operationId`) | Variants | Provider state | Used by |
| --- | --- | --- | --- | --- |
| K1 | `getCart` | 200 anonymous with `X-Cart-Token`; 200 account cart with bearer; 200 empty cart without token; 404 unknown token | `an anonymous cart with token tok-cart-1 holds 2 lines`, `ana@example.com has an account cart with 1 line`, `no cart exists for token tok-unknown` | cart, header badge |
| K2 | `addCartLine` | 201 first write returns `X-Cart-Token` response header (anonymous); 201 with token; 404 unknown product; 422 insufficient stock or out of stock | `no anonymous cart exists`, `an anonymous cart with token tok-cart-1 holds 2 lines`, `product 0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21 is active with stock 0` | product, cart |
| K3 | `updateCartLineQuantity` | 200 recalculated cart; 200 quantity 0 removes the line; 422 above stock | `an anonymous cart with token tok-cart-1 holds 2 lines` | cart |
| K4 | `removeCartLine` | 200 cart; 404 unknown line | `an anonymous cart with token tok-cart-1 holds 2 lines` | cart |
| K5 | `clearCart` | 204 | `an anonymous cart with token tok-cart-1 holds 2 lines` | cart (not used by checkout; order clears the cart) |
| K6 | `mergeCart` | 200 `MergeResult` with `cappedLines`; 404 token consumed; 409 merge in progress | `an anonymous cart with token tok-cart-1 holds 2 lines and ana@example.com has an account cart`, `the anonymous cart tok-cart-1 was already merged`, `a merge for tok-cart-1 is in progress` | sign-in |
| K7 | `getCart` after price change | 200 with `priceChanged: true` and `revision` | `the price of a line in the cart of ana@example.com changed since it was added` | cart, checkout |

## Storefront to order (`storefront-order.json`, verified by order)

| # | Interaction (`operationId`) | Variants | Provider state | Used by |
| --- | --- | --- | --- | --- |
| O1 | `placeOrder` with mandatory `Idempotency-Key` | 201 `Order` (`orderStatus` placed, `paymentStatus` approved) | `ana@example.com has a cart at revision rev-7f3a9c21 with stock available and owns address 5f0c1a52-3a43-4a53-9f58-7a1d8b9d2c11` | checkout |
| O2 | `placeOrder` | same key and body replays the same 201 (one order) | `ana@example.com already placed an order with Idempotency-Key key-1` | checkout retry (FR-008) |
| O3 | `placeOrder` | 409 `price-changed` with old and new prices per line; 409 `insufficient-stock` naming the lines; 409 `order-cancelled` | `the price of a line changed after revision rev-7f3a9c21`, `a line in the cart of ana@example.com exceeds available stock` | checkout (FR-007) |
| O4 | `placeOrder` | 422 `payment-declined` with category; 422 `idempotency-key-reuse` (same key, different body) | `payment is declined for token tok_sim_decline_01`, `ana@example.com already placed an order with Idempotency-Key key-1` | checkout |
| O5 | `placeOrder` | 201 with `paymentStatus` pending and `paymentExpiresAt` (additive field of the order contract, present while the payment is pending; the storefront shows the countdown from it and never recomputes the window) | `payment is pending for token tok_sim_unreachable` | confirmation (FR-009) |
| O6 | `listOwnOrders` | 200 page newest first; 200 empty page | `ana@example.com has 3 orders`, `ana@example.com has no orders` | orders |
| O7 | `getOwnOrder` | 200 with `statusHistory`; 200 pending payment (polled every 5 s; later 200 approved); 404 another shopper's order | `ana@example.com owns order 0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10`, `the payment of order 0b9a3b0e-... is pending and becomes approved`, `order 0b9a3b0e-... belongs to another shopper` | confirmation, order |
| O8 | `cancelOwnOrder` | 200 `cancelled`; 409 `order-not-cancellable` | `ana@example.com owns a placed order 0b9a3b0e-...`, `ana@example.com owns a shipped order 0b9a3b0e-...` | order |
| O9 | `listOwnOrders` (console, operator: all orders) | 200 page including orders of other shoppers; 403 for a shopper where the contract refuses | `an operator ops@example.com is signed in and 5 orders of 2 shoppers exist` | console orders |
| O10 | `getOwnOrder` (console, operator: any order) | 200 | `an operator ops@example.com is signed in and order 0b9a3b0e-... exists` | console order |
| O11 | `transitionOrderStatus` (console) | 200 new status; 409 `invalid-transition`; 409 preparing without approved payment; 403 shopper | `order 0b9a3b0e-... is placed with payment approved`, `order 0b9a3b0e-... is delivered`, `a shopper ana@example.com is signed in` | console order (FR-011) |

## Storefront to payment (`storefront-payment.json`, verified by payment)

| # | Interaction (`operationId`) | Variants | Provider state | Used by |
| --- | --- | --- | --- | --- |
| P1 | `getSimulatorRules` (operator) | 200 rule document (`version`, `defaultOutcome`, `rules[]` with the tokens `tok_sim_approve_4242`, `tok_sim_decline*`, `tok_sim_unreachable`, `tok_sim_unreachable_forever`, amount rules ending 13 and 14); 403 shopper | `the simulator rules document version 2 is active`, `a shopper ana@example.com is signed in` | payment-method options of checkout in local mode and the console; the storefront renders the options from this document when the caller is an operator and from its build-time list of the same seeded tokens for a shopper (the 004 route is operator only; no card number is ever typed or stored, FR-006) |
| P2 | `getPaymentAttempt`, `listPaymentAttemptsForOrder` | 200 attempt with outcome and decline category; 200 empty list | `order 0b9a3b0e-... has a declined payment attempt`, `order 0b9a3b0e-... has no payment attempts` | order page, confirmation (declined retry hint) |

## Storefront to gateway (`storefront-gateway.json`, verified by the gateway; new)

The pact describes the behaviours of [`openapi/gateway-browser-session.yaml`](openapi/gateway-browser-session.yaml),
[`openapi/telemetry.yaml`](openapi/telemetry.yaml) and the catch-all route of [`gateway-routes.md`](gateway-routes.md).
Cookie names in the pact are the plain HTTP names (`session`, `cart`) because the provider verification runs over HTTP; one
additional interaction (G15) covers the HTTPS names with `X-Forwarded-Proto: https`.

| # | Interaction | Variants | Provider state | Used by |
| --- | --- | --- | --- | --- |
| G1 | `POST /api/v1/identity/sessions` with `X-Browser-Session: cookie` | 200 body exactly `{expiresAt, roles}` (no token members) and `Set-Cookie: session=...; HttpOnly; SameSite=Strict; Path=/` (no `Max-Age`, no `Domain`) | `identity accepts the credentials of ana@example.com and issues a token pair` | sign-in |
| G2 | same | identity's 401, 403, 422, 429 passed through unchanged, no `Set-Cookie` | `identity rejects the credentials of ana@example.com`, `identity reports ana@example.com as unverified`, `identity throttles ana@example.com` | sign-in states |
| G3 | `POST /api/v1/identity/sessions/refresh` with the cookie | 200 `{expiresAt, roles}` and a re-set cookie; 401 `unauthorized` plus cookie deletion when identity refuses the refresh | `a session cookie for ana@example.com exists and identity rotates refresh tokens`, `identity rejects the refresh token` | renewal |
| G4 | any cookie-authenticated request, for example `GET /api/v1/identity/accounts/me` | the upstream receives `Authorization: Bearer ...`; response re-sets the cookie (silent renewal) | `a session cookie for ana@example.com exists` | page-load session probe |
| G5 | same | access token about to expire: gateway refreshes through identity first, then forwards | `a session cookie for ana@example.com exists whose access token expires in 30 seconds` | silent renewal |
| G6 | same | 401 `unauthorized` and cookie deletion when idle for more than 30 minutes | `a session cookie for ana@example.com exists whose last activity was 31 minutes ago` | idle expiry (FR-014) |
| G7 | same | 401 `unauthorized` and cookie deletion for a tampered or unsealable cookie | `a session cookie that cannot be unsealed exists` | idle expiry, tamper |
| G8 | `DELETE /api/v1/identity/sessions/current` with the cookie | 204, identity receives the revocation, `Set-Cookie` deletes the cookie | `a session cookie for ana@example.com exists` | sign-out |
| G9 | non-GET with the cookie (for example `POST /api/v1/cart/lines`) | 403 `forbidden` for `Sec-Fetch-Site: cross-site` and for a mismatching `Origin`; allowed for `same-origin` and `none` | `a session cookie for ana@example.com exists` | cross-site protection |
| G10 | any request with the cookie and an `Authorization` header | 400 `validation`, nothing forwarded | `a session cookie for ana@example.com exists` | bearer-and-cookie refusal |
| G11 | `POST /api/v1/cart/lines` with `X-Browser-Session: cookie`, no cart cookie | cart answers with `X-Cart-Token`; response has no `X-Cart-Token` and sets `cart=...; HttpOnly; SameSite=Lax; Path=/; Max-Age=2592000` | `no anonymous cart exists` | add to cart |
| G12 | `GET /api/v1/cart` with the cart cookie and no `X-Cart-Token` | cart upstream receives `X-Cart-Token` from the cookie; an explicit `X-Cart-Token` is not overridden | `a cart cookie for token tok-cart-1 exists` | cart persistence (FR-004) |
| G13 | `POST /api/v1/cart/merge` with both cookies | upstream receives bearer and token; on 200 the cart cookie is deleted; on 409 it is kept | `a session cookie for ana@example.com and a cart cookie for token tok-cart-1 exist`, `a merge for tok-cart-1 is in progress` | sign-in merge |
| G14 | non-GET without a session cookie on a route that needs none (anonymous cart write) | no origin check, request forwarded | `no cookies exist` | anonymous cart |
| G15 | sign-in over HTTPS (`X-Forwarded-Proto: https`) | `Set-Cookie: __Host-session=...; HttpOnly; Secure; SameSite=Strict; Path=/`; the plain name is deleted | `identity accepts the credentials of ana@example.com and issues a token pair` | HTTPS deployments |
| G16 | `POST /api/v1/telemetry/v1/traces` and `/logs`, anonymous | 200 with the collector's `{}` and partial-success body; collector receives no `Cookie` and no `Authorization` and the rewritten path `/v1/traces` or `/v1/logs` | `the telemetry collector accepts OTLP` | telemetry |
| G17 | same | 413 `payload-too-large` above 256 KiB; 429 `throttled` with `Retry-After`; 503 `unavailable` when the collector is not running | `the telemetry collector accepts OTLP` (413, 429 with `the browse budget of the client is exhausted`), `the telemetry collector is not running` | telemetry drop rules (FR-032) |
| G18 | `GET /` and `GET /products/0b4e6d1c-...` | 200 HTML shell from the storefront upstream with the storefront `Content-Security-Policy` (no `unsafe-inline`), `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`; `index.html` is `no-store` | `the storefront upstream serves index.html` | page load |
| G19 | `GET /api/v1/unknown` and `POST /products/1` | 404 `not-found` problem; never the shell | `the storefront upstream serves index.html` | deny by default |

## Verification topology

| Pact | Provider | Provider test location | Needs |
| --- | --- | --- | --- |
| `storefront-identity` | identity | `services/identity/src/contractTest` | its own fixtures |
| `storefront-catalog` | catalog | `services/catalog/src/contractTest` | its own fixtures |
| `storefront-cart` | cart | `services/cart/src/contractTest` | catalog stubbed as in the cart's own provider tests |
| `storefront-order` | order | `services/order/src/contractTest` | cart, catalog, payment, identity stubbed (their pacts are verified by their providers) |
| `storefront-payment` | payment | `services/payment/src/contractTest` | its own fixtures |
| `storefront-gateway` | gateway | `services/gateway/src/contractTest` | WireMock identity, cart, collector and storefront upstreams; a fixed clock for G5 and G6; a fixed `BROWSER_SESSION_KEY` |

`./gradlew -q contractTest contractVerify` runs the storefront consumer first (it writes `build/pacts`), then every provider
tagged `provider`. A change to this matrix requires updating the matching OpenAPI contract or `gateway-routes.md` in the same
change (rule 3 of feature 004).

## Provider change requested from feature 004

| Provider | Change | Why | Compatibility |
|----------|--------|-----|---------------|
| order | `Order` gains `paymentExpiresAt` (date-time, present while `paymentStatus` is `pending`, absent otherwise) in `specs/004-ecommerce-platform-mvp/contracts/openapi/order.yaml` and `contracts/openapi/order.yaml`, computed from the order service's configured payment window | FR-009 countdown without duplicating the server's window rule in the browser | Additive (constitution VI); existing consumers ignore the field; verified by the storefront pact rows O5 and O7 |
