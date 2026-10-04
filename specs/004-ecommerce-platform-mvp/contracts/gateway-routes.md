# Gateway Routes and Cross-Cutting Behaviour

Feature: 004-ecommerce-platform-mvp. Covers FR-005, FR-023, FR-024, FR-025 and user story 8.
The gateway is the single public entry point; every public path is versioned as
`/api/v1/<context>/...` and matches the `paths` of the OpenAPI file of the target service.

## Route table

Target hosts are DNS names on the platform network, resolved through service discovery so that
instances can be added or removed without changing gateway configuration (FR-024). The names are the Compose service names (`identity`, `catalog`, `cart`, `order`, `payment`, `notification`; `<NAME>_URL` of the gateway, e.g. `IDENTITY_URL=http://identity:8080`).

| Public route prefix | Target service (DNS name) | Auth requirement | Rate limit tier |
| --- | --- | --- | --- |
| `/api/v1/identity/` (register, verify, sign in, reset, profile, addresses) | `identity` | anonymous for register, sign in, verify, reset; authenticated (shopper or operator) for profile, addresses, notification preferences and sign-out; shopper for account deletion | `auth` for the anonymous credential endpoints (register, verify e-mail, sign in, refresh, password reset), `standard` otherwise |
| `/api/v1/catalog/` (GET products, categories, search) | `catalog` | anonymous | `browse` |
| `/api/v1/catalog/` (POST and PUT: create and update products and categories, `POST .../stock-adjustments`, `POST .../images`, `POST .../withdrawal` and `POST .../reinstatement` for products and categories; the gateway routes no PATCH and no catalog DELETE) | `catalog` | operator | `operator` |
| `/api/v1/cart` | `cart` | anonymous (identified by `X-Cart-Token`) or authenticated (shopper or operator) for the account cart; `POST /api/v1/cart/merge` requires an authenticated caller (shopper or operator) | `standard` |
| `/api/v1/orders/` | `order` | shopper (place, list, get, cancel); operator for `POST /{id}/status` and reading any order | `checkout` for `POST /api/v1/orders`, `standard` otherwise, `operator` for operator calls |
| `/api/v1/payments/` | `payment` | operator or owning shopper (attempts, refunds); operator only for `/simulator/rules` | `standard` |
| `/api/v1/notifications/` | `notification` | shopper (own list); operator for `/failed` and `/{id}/retry` | `standard`, `operator` for operator calls |
| `/actuator/health`, `/actuator/prometheus` | not routed | not public | not applicable |

Notes:

- The gateway authenticates (valid JWT) and applies coarse role checks per route. Every service
  repeats the authorisation decision itself (deny by default, FR-005); the gateway is not the only
  line of defence.
- "anonymous" means no token is required; a token that is present but invalid is still rejected
  with 401 so that expired sessions do not silently become anonymous.
- Health and metrics endpoints are reachable only from the internal observability network.

## Rate limit tiers

Limits are per client key (source address for anonymous calls, account id for authenticated
calls) and are configuration, not contract. Exceeding a limit returns 429 `application/problem+json`
with `Retry-After`. Registration (`POST /api/v1/identity/accounts`) and e-mail verification
(`POST /api/v1/identity/accounts/verify-email`) are in the `auth` tier: they are anonymous and
credential-adjacent, and each registration sends a verification e-mail, so the per-address budget
also limits mass registration and mail flooding. Default values for the MVP:

| Tier | Default limit | Purpose |
| --- | --- | --- |
| `auth` | 10 requests per minute per source address | Credential stuffing, mass registration and verification-mail flooding; complements account throttling (FR-006) |
| `browse` | 600 requests per minute per client | Read-heavy catalogue traffic |
| `standard` | 120 requests per minute per client | Normal authenticated use |
| `checkout` | 20 requests per minute per account | Order placement, protects stock and payment |
| `operator` | 300 requests per minute per account | Back-office operations |

## Cross-cutting behaviour

1. **JWT validation.** The gateway validates the bearer token signature, issuer, audience, expiry
   and not-before using the identity service's JWKS endpoint (`identity`, covered by the
   gateway -> identity pact). Keys are cached and refreshed on unknown `kid` or on schedule; if
   the JWKS cannot be fetched and no cached key is valid, protected routes answer 503 and never
   fall back to accepting the token. Validated claims (subject, roles) are passed downstream in
   headers the gateway sets; client-supplied copies of those headers are stripped.
2. **Deny by default.** A request whose method and path match no route in the table receives a
   404 `application/problem+json` response and nothing is forwarded to any service. New routes
   exist only when added to gateway configuration with an explicit auth requirement.
3. **Correlation id.** `X-Correlation-Id` is accepted if it is a UUID or a 16 to 64 character
   token of `[A-Za-z0-9-]`. A missing value is generated. A malformed or oversized value is
   replaced by a new one and the original is recorded in the access log field
   `originalCorrelationId` for investigation. The id is forwarded to every service, propagated
   into events, logged in structured form by every service and echoed in every response
   (including errors generated by the gateway).
4. **Security headers.** Responses carry `Strict-Transport-Security`, `X-Content-Type-Options:
   nosniff`, `X-Frame-Options: DENY`, `Cache-Control: no-store` on authenticated routes, and a
   restrictive `Content-Security-Policy`. Internal headers and `Server` details are removed.
5. **Request size limit.** Request bodies are limited to 1 MiB; the catalogue image upload route
   is limited to 5 MiB. Larger requests are refused with 413 before reaching a service. Header
   size and request time are also bounded.
6. **Errors.** Gateway-originated errors (401, 404, 413, 429, 502/503 when no healthy instance
   exists) use the same RFC 9457 `Problem` shape as the services.
7. **Retries and timeouts.** Upstream timeouts are applied per tier. Idempotent methods
   (GET, HEAD) are retried automatically on another instance when no connection could be opened;
   the identity POSTs are retried on the same connection errors only (nothing was sent);
   `POST /api/v1/orders` is never retried by the gateway because it relies on the client's
   `Idempotency-Key`.
8. **Network isolation.** Services are attached only to the internal platform network and publish
   no host ports; the gateway is the sole container on the public-facing network (FR-023).
   Service-to-service calls (for example order -> payment authorise and refund) use the internal
   network and are not routed by the gateway. Stopping one instance of a service leaves requests
   served by the remaining instances (SC-008).

## Consistency rules

- A path in an OpenAPI file that is not in this table is a contract defect.
- Changing a route's auth requirement or tier requires updating this file and the matching
  OpenAPI `security` and role descriptions in the same change.
