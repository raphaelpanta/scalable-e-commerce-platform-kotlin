# Storefront Routes

Feature: 005-storefront-dev-bootstrap. Covers FR-001 to FR-003, FR-005, FR-009 to FR-012, FR-016. These are the URL
routes of the single-page application, served by the gateway's `storefront` catch-all ([gateway-routes.md](gateway-routes.md))
and implemented with React Router data routers in `frontend/src/ui/routes/`. `operationId`s are those of
`specs/004-ecommerce-platform-mvp/contracts/openapi/*.yaml`; operations marked (gw) are those of
[`openapi/gateway-browser-session.yaml`](openapi/gateway-browser-session.yaml). Every API request carries
`X-Browser-Session: cookie` and `X-Correlation-Id`; no route reads or stores a token.

## Access levels

| Level | Meaning | When the rule is not met |
| --- | --- | --- |
| anonymous | anyone, signed in or not | n/a |
| shopper | a signed-in account (role `shopper`; an operator account is also signed in and may open these pages) | unauthenticated: redirect rule below |
| operator | a signed-in account whose session summary `roles` contains `operator` | unauthenticated: redirect rule below; signed in without the role: the "not allowed" state (the page renders no console content; console links are hidden for non-operators). The platform's 403 is what actually refuses (FR-012) |

The signed-in state comes from `getOwnProfile` on page load (200 signed in, 401 anonymous) and from the sign-in summary
`{expiresAt, roles}`; the UI only hides, services authorise.

## Redirect rule for protected routes

An unauthenticated visit to any route marked shopper or operator, or any 401 `unauthorized` received while using one,
redirects (replace, not push) to `/sign-in?next=<path>` where `<path>` is the current path plus query, URL-encoded.

`next` is accepted only when all of these hold; otherwise it is ignored and the target after sign-in is `/`:

| Rule | Rejected examples |
| --- | --- |
| starts with exactly one `/` | `//evil.example`, `/\evil.example`, `https://evil.example`, `javascript:alert(1)` |
| contains no scheme, host, backslash, control character or encoded form of those after one decoding | `/%2F%2Fevil.example` |
| its path matches one of the routes of this file other than `/sign-in`, `/register`, `/verify-email`, `/forgot-password`, `/reset-password` | `/api/v1/identity/accounts/me`, `/unknown` |

After a successful sign-in the storefront merges the anonymous cart (`mergeCart` (gw), result shown) and navigates to the
accepted `next`. The same validation applies to any other redirect target the app builds.

## Route table

| Path | Access | Operations called | URL-encoded state (FR-003) |
| --- | --- | --- | --- |
| `/` | anonymous | `listProducts`, `listCategories`, `getCart` (header count) | `?page=` (zero-based as the API), `?size=` when not the default 20 |
| `/categories/:slugOrId` | anonymous | `getCategory`, `listProducts` (`categoryId`), `listCategories` | path: category id (UUID; a readable slug prefix is accepted and ignored, resolution is by id; any other value shows the not-found page); `?page=`, `?size=` |
| `/search` | anonymous | `listProducts` (`q`) | `?q=` (trimmed, max 100 characters), `?page=`, `?size=`; an empty `q` shows the all-products list with a prompt |
| `/products/:id` | anonymous | `getProduct`, `addCartLine` (gw), `getCart` | path: product id; no other state (quantity is local) |
| `/cart` | anonymous | `getCart`, `updateCartLineQuantity`, `removeCartLine`, `clearCart` (gw cookie injection), `mergeCart` after sign-in | none |
| `/sign-in` | anonymous (a signed-in visitor is redirected to `next` or `/`) | `signIn` (gw `browserSignIn`), `mergeCart` (gw), `getCart` | `?next=` (validated as above) |
| `/register` | anonymous | `registerAccount` | none |
| `/verify-email` | anonymous | `verifyEmail` | `?token=` read once on load, then removed from the address (history replace) before the request is sent; the page renders only success or a generic "link invalid or expired" |
| `/forgot-password` | anonymous | `requestPasswordReset` | none; the confirmation is the same whether or not the email exists (FR-005) |
| `/reset-password` | anonymous | `completePasswordReset` | `?token=` read once and removed from the address like `/verify-email`; missing token shows the generic invalid-link state |
| `/checkout` | shopper | `getCart`, `listOwnAddresses`, `addOwnAddress`, `getSimulatorRules` (operator) or the build-time list of seeded simulated methods (shopper), `placeOrder` | `?step=address`, `?step=payment` or `?step=review`; the draft (address id, payment method id, acknowledged `cartRevision`, `Idempotency-Key`) lives in `sessionStorage`, never a card number or personal data beyond an address id (FR-006, FR-008) |
| `/orders/:id/confirmation` | shopper | `getOwnOrder` (polled every 5 s while `paymentStatus` is `pending`, stopped on a final status or when createdAt plus 30 minutes has passed), `listPaymentAttemptsForOrder` | path: order id |
| `/orders` | shopper | `listOwnOrders` | `?page=`, `?size=` |
| `/orders/:id` | shopper | `getOwnOrder` (same polling), `cancelOwnOrder` (after confirmation dialog), `getPaymentAttempt` | path: order id |
| `/account` | shopper | `getOwnProfile`, `updateOwnProfile`, `deleteOwnAccount` (after confirmation, `browserSignOut` (gw) afterwards), `browserSignOut` (gw) | none |
| `/account/addresses` | shopper | `listOwnAddresses`, `addOwnAddress`, `updateOwnAddress`, `deleteOwnAddress` | `?page=` when more than one page |
| `/account/notifications` | shopper | `getOwnNotificationPreferences`, `updateOwnNotificationPreferences`, `requestPhoneVerification`, `confirmPhoneVerification` | none |
| `/console/orders` | operator | `listOwnOrders` (an operator receives all orders), `getOwnOrder` (row details) | `?status=` (placed, preparing, shipped, delivered, cancelled; filtered within the page client-side where the contract has no parameter), `?page=`, `?size=` |
| `/console/orders/:id` | operator | `getOwnOrder`, `transitionOrderStatus` (confirmation per change; only the transitions the platform allows are offered), `listPaymentAttemptsForOrder` | path: order id |
| `/console/stock` | operator | `listProducts` (`includeWithdrawn` for visibility only), `getProduct`, `adjustStock` (reason required) | `?q=`, `?page=`, `?size=` |
| any other path | anonymous | none | none: the not-found page (200 shell from the gateway, FR-002) with links to `/` and `/search` |

Navigation is never an API call by itself: a route that only needs a signed-in check uses the cached session state.
Product, category and order ids in paths are validated as UUIDs before any request is made; an invalid id renders the
not-found page.

## States every route must render (FR-016)

Each list, detail and action renders all four states below; the storefront derives them from the platform's answer, never
from internal details. Text comes from the problem `title` and `detail` only after mapping to shopper-readable copy.

| State | Trigger | Rendering |
| --- | --- | --- |
| loading | request in flight | a skeleton or status text (`role="status"`), no layout shift; actions disabled while pending |
| empty | 200 with no items (or an empty cart), or a 404 on a collection-like lookup | an explanatory message and one next action (browse products, add a product, place a first order) |
| error | 4xx/5xx other than throttling, network failure | a readable message with a retry action, the correlation id in a collapsible "support details", no stack traces, no raw problem JSON; field errors (422 `errors[]`) next to the field; 401 handled by the redirect rule; 404 on a detail route renders the not-found page |
| throttled | 429 `throttled` with `Retry-After` | "Too many requests, try again in N seconds" with a visible countdown; the retry control is disabled until it ends; no automatic retry loop, TanStack Query retry is disabled for 429 |

Route-specific states:

| Route | Additional required states |
| --- | --- |
| `/products/:id` | out of stock: "Add to cart" disabled and explained; withdrawn or unknown: not-found page; failed image: neutral placeholder |
| `/cart` | empty cart; line with `priceChanged`: both prices shown; capped lines after merge listed (`cappedLines`) |
| `/checkout` | `409 price-changed`: old and new price per line and an explicit accept button before resubmission (FR-007); `409 insufficient-stock`: the named lines; `422 payment-declined`: decline category message; session expiry: redirect to sign-in and return to the same step with the draft intact; double submit blocked, the same `Idempotency-Key` reused on resend (FR-008) |
| `/orders/:id/confirmation`, `/orders/:id` | `paymentStatus` pending: "awaiting payment" with the remaining time (from the order's additive `paymentExpiresAt` field, see pact-matrix.md) and automatic refresh; failed payment; cancelled with reason; cancel offered only when `orderStatus` is `placed`, with `409 order-not-cancellable` shown |
| `/console/*` | "not allowed" for a signed-in non-operator; `409 invalid-transition` shown without changing the displayed status; stock adjustment `422` next to the field |
| `/verify-email`, `/reset-password` | verifying (loading), verified, invalid or expired link (single generic message) |
| `/sign-in` | invalid credentials (generic message), unverified email (`403`), account throttled (429 countdown) |
