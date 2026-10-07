# Gateway Routes: Additions for the Storefront

Feature: 005-storefront-dev-bootstrap. Covers FR-013, FR-014, FR-031, FR-032 and research sections 1 to 4
and 6. Additive to [`specs/004-ecommerce-platform-mvp/contracts/gateway-routes.md`](../../004-ecommerce-platform-mvp/contracts/gateway-routes.md):
every row, tier, limit and cross-cutting rule of that file stays in force unless a row below says otherwise.
The behaviour of the cookies is specified in [`openapi/gateway-browser-session.yaml`](openapi/gateway-browser-session.yaml)
and of the telemetry routes in [`openapi/telemetry.yaml`](openapi/telemetry.yaml).

## Route table additions

Route ids in `services/gateway/src/main/resources/application.yml` today: `identity-*`, `catalog-*`, `cart*`,
`order-*`, `payment-*`, `notification-*`. The ids below are new and do not clash.

| Route id | Method | Public path | Target (env var, DNS name) | Auth requirement | Tier | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| `telemetry-traces` | `POST` | `/api/v1/telemetry/v1/traces` | `OTEL_COLLECTOR_URL` (Compose `http://otel-collector:4318`), path rewritten to `/v1/traces` | anonymous | `browse` (per source address) | `max-body-size: 256KB`; `StripPrefix` of `/api/v1/telemetry`; `Cookie` and `Authorization` removed before forwarding; no gateway retry; 503 `unavailable` when the collector does not answer |
| `telemetry-logs` | `POST` | `/api/v1/telemetry/v1/logs` | `OTEL_COLLECTOR_URL`, rewritten to `/v1/logs` | anonymous | `browse` (per source address) | same as `telemetry-traces` |
| `storefront` | `GET`, `HEAD` | every path that is not under `/api/`, `/actuator/` or `/.well-known/` | `STOREFRONT_URL` (Compose `http://storefront:8080`; default `http://localhost:8082` outside Compose) | anonymous | `browse` | route `order` after every `/api/**` route; storefront CSP replaces the API CSP on this route only (below); no session cookie processing |

Notes:

- `telemetry-*` and `storefront` carry the same `metadata` keys as every route (`auth`, `tier`, `response-timeout`);
  `telemetry-*` additionally `max-body-size: 256KB` (a route may lower the 1 MiB default as well as raise it).
- The `storefront` predicate MUST exclude `/api`, `/api/**`, `/actuator/**` and `/.well-known/**` (implemented as the
  `NotPath` predicate), so that an unknown `GET`
  below those prefixes still answers 404 `not-found` and is never served the SPA shell. Everything else, including
  `/`, `/products/123` and `/assets/app-3f9c.js`, is forwarded to the static container.
- **Unknown paths.** Deny by default is unchanged: a non-`GET`/`HEAD` request to any path that matches no route
  (including `POST /`, `POST /products/1` and any non-`POST` method on `/api/v1/telemetry/**`) answers 404 `not-found`
  `application/problem+json` and nothing is forwarded. A `GET`/`HEAD` to an unknown path outside the three excluded
  prefixes is served by the static container, which answers the SPA shell (`index.html`, 200) so that the storefront
  renders its own not-found page, except for paths whose last segment has a file extension, which the static
  container answers 404 (passed through as is).
- The route table of feature 004 gains no new `/api/**` operation besides the two telemetry ones. The browser-session
  behaviour changes no route, method or tier; it changes how credentials arrive and what is returned for the existing
  session and cart routes when a cookie or `X-Browser-Session: cookie` is present.
- `/actuator/**` and `/.well-known/**` stay not routed publicly (404), exactly as in feature 004.

## Storefront route caching and security headers

| Response | Header | Value |
| --- | --- | --- |
| `index.html` and any SPA-shell response | `Cache-Control` | `no-store` |
| Hashed assets (`/assets/*`, file name carries a content hash) | `Cache-Control` | `public, max-age=31536000, immutable` |
| Storefront route, all responses | `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: https:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'` |
| Storefront route, all responses | `X-Frame-Options` | `DENY` |
| Storefront route, all responses | `Referrer-Policy` | `no-referrer` |
| Storefront route, all responses | `Permissions-Policy` | denies camera, microphone, geolocation and payment |
| Storefront route, all responses | `Strict-Transport-Security`, `X-Content-Type-Options: nosniff` | unchanged from feature 004 cross-cutting rule 4 |

The strict API policy (`default-src 'none'`) stays on every `/api/**` response; the storefront policy lives in
`EdgeHeaders` and applies by matching the `storefront` route only. The cache headers are set by the static container
(`platform/docker/storefront/nginx.conf`); the gateway passes them through and does not add `no-store` to
this anonymous route. `img-src https:` exists because product image URLs are external, registered by operators.

## New filters and their place in the chain

Global filter order in the gateway (lower runs first). Existing: `RouteAccessFilter` (-300), `RateLimitFilter` (-200),
`RequestSizeFilter` (-150). New:

| Order | Filter | Responsibility | Why here |
| --- | --- | --- | --- |
| -400 | `BrowserSessionFilter` | Unseal the session cookie, enforce idle expiry (30 min), the cross-site check (`Sec-Fetch-Site`, `Origin`) and the cookie-plus-`Authorization` refusal; refresh through identity when the access token has under 60 s left; inject `Authorization: Bearer`; re-set, delete or replace the cookie on the way back; rewrite the sign-in and refresh 200 bodies to `{expiresAt, roles}` in browser mode | Must run **before** `RouteAccessFilter` so the injected bearer is validated and role-checked like any API call, and before `RateLimitFilter`, whose per-account key comes from the identity `RouteAccessFilter` extracts from that bearer |
| -350 | `CartCookieFilter` | Inject `X-Cart-Token` from the cart cookie, mirror a returned `X-Cart-Token` into the cart cookie, remove it from browser responses, delete the cookie after a successful `POST /api/v1/cart/merge` | After `BrowserSessionFilter` (the merge needs the bearer already injected), before `RouteAccessFilter` (the cart token is an anonymous credential) |
| -300 | `RouteAccessFilter` | unchanged | sees one bearer, whether it came from a header or a cookie |
| -200 | `RateLimitFilter` | unchanged | unchanged keys: account id when authenticated, source address otherwise |
| -150 | `RequestSizeFilter` | unchanged; honours `max-body-size: 256KB` on the telemetry routes | unchanged |

Rules:

- Both filters act only on routes that match; a request that matches no route is refused with 404 before any cookie is
  read. They do nothing on `storefront`, `telemetry-traces` and `telemetry-logs` (the telemetry routes strip `Cookie`).
- `BrowserSessionFilter` ignores the session cookie on the anonymous credential routes other than sign-in and refresh
  (`identity-registration`, password reset in `identity-credentials`) and ignores an existing cookie on sign-in; see the
  OpenAPI file.
- Errors generated by the filters (401 `unauthorized`, 403 `forbidden`, 400 `validation`) use the platform `Problem`
  shape, carry `X-Correlation-Id` and `Cache-Control: no-store`, and are logged without cookie values.
- Cookie values and the bearer injected from them are never logged and never copied to telemetry.
- Identity failures during a cookie request: identity unreachable while refreshing ahead → 503 `unavailable` with the
  cookie kept (the next request retries); an unreadable or oversized token pair on sign-in or refresh → 502
  `unavailable`, fail closed, no cookie set.
- A request carrying both cart cookie names has both ignored and deleted; a request carrying both session cookie names
  is an unsealable session (401, both deleted).
- Browser-mode sign-in and refresh are forwarded without the client's `Accept-Encoding`, so the gateway can rewrite the
  JSON body it receives.

## Cookie names by transport

`__Host-` prefixed cookies require `Secure` and are only reliably accepted over HTTPS, so the name depends on how the
request arrived (scheme `https` or `X-Forwarded-Proto: https` versus plain HTTP such as `http://localhost`):

| Request arrived over | Session cookie | Cart cookie | `Secure` | Other attributes |
| --- | --- | --- | --- | --- |
| HTTPS | `__Host-session` | `__Host-cart` | yes | session: `HttpOnly; SameSite=Strict; Path=/`, no `Domain`, no `Max-Age`; cart: `HttpOnly; SameSite=Lax; Path=/`, no `Domain`, `Max-Age=2592000` |
| plain HTTP (local development) | `session` | `cart` | no | identical to the HTTPS row |

The gateway accepts whichever name is present and never sets both: when it sets one name it deletes the other in the
same response; a request carrying both names is treated as an unsealable session (401 `unauthorized`, both deleted).

## New environment variables (gateway)

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `STOREFRONT_URL` | no | `http://localhost:8082` outside Compose; Compose sets `http://storefront:8080` | Upstream of the `storefront` route |
| `OTEL_COLLECTOR_URL` | no | `http://localhost:4318` outside Compose; Compose sets `http://otel-collector:4318` | Upstream of the telemetry routes (the collector's OTLP/HTTP receiver, without a path) |
| `BROWSER_SESSION_KEY` | **yes**, outside the `dev` and `test` profiles | none | AES-256-GCM key sealing both cookies: 32 random bytes, Base64. Shared by every gateway replica (rotating it signs every browser out). The gateway refuses to start without it, or with a value that does not decode to exactly 32 bytes, in any profile other than `dev` and `test` (same rule as identity's `IDENTITY_SIGNING_KEY`); in `dev` and `test` a per-process random key is used. Generated into `platform/compose/.env` by `scripts/dev-env.sh`; never committed, logged or echoed |

Also document the three variables in `docs/service-conventions.md` and add `BROWSER_SESSION_KEY=` to
`platform/compose/.env.example`.

## Rate limit and request-size summary

| Route | Tier | Key | Body limit |
| --- | --- | --- | --- |
| `storefront` | `browse` | source address | n/a (`GET`/`HEAD`) |
| `telemetry-traces`, `telemetry-logs` | `browse` | source address | 256 KiB, then 413 `payload-too-large` |

Exceeding a tier returns 429 `throttled` with `Retry-After`; the storefront honours it (FR-016) and its telemetry
exporter pauses for that long and drops the batch.

## Consistency rules

- The telemetry paths in `openapi/telemetry.yaml` and the browser-session paths in
  `openapi/gateway-browser-session.yaml` MUST match this table; a path in either file that is not here (or in the
  feature 004 table) is a contract defect.
- Changing a CSP value, cookie attribute, filter order or the 256 KiB limit requires updating this file, the matching
  OpenAPI file and the gateway tests in the same change.

## Provider change outside the gateway

The only change this feature asks of a feature 004 service is additive: the order contract's `Order` gains
`paymentExpiresAt` (see [pact-matrix.md](pact-matrix.md), "Provider change requested from feature 004").
Everything else the storefront needs already exists; payment-method options for shoppers come from a
build-time list of the seeded simulator tokens because `getSimulatorRules` is operator-only.
