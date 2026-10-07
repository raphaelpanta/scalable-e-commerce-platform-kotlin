# API gateway (`services/gateway`)

The single public entry point (port 8080; management on 8081). The specification is
`specs/004-ecommerce-platform-mvp/contracts/gateway-routes.md`; this page describes how the module implements it.
Spring Cloud Gateway (server WebFlux, `spring.cloud.gateway.server.webflux.*`) in Kotlin, package
`com.ecommerce.gateway`.

## Route table

Routes live in `services/gateway/src/main/resources/application.yml`. Each route matches an exact set of public
paths and methods of `contracts/openapi/*.yaml` and declares its policy in `metadata`:

```yaml
- id: order-placement
  uri: ${ORDER_URL:http://localhost:8080}
  predicates:
    - Path=/api/v1/orders
    - Method=POST
  metadata: { auth: shopper, tier: checkout, response-timeout: 30000 }
```

`auth` is `anonymous`, `authenticated` (any valid token: `shopper` or `operator`), `shopper` or `operator`; `tier`
is a rate-limit tier; `operator-tier` (optional) is the tier charged when the caller holds the `operator` role;
`max-body-size` (optional) overrides the 1 MiB body limit; `response-timeout` must equal the tier's upstream timeout.
A route without `auth`/`tier`, with an unknown value or with another timeout stops the gateway at start-up
(`RoutePolicy`, `RoutePolicies`). `RouteTableTest` checks every operation of the OpenAPI files against the table.

| Route id | Methods and paths (`/api/v1/...`) | Auth | Tier |
|---|---|---|---|
| `identity-credentials` | POST `identity/sessions`, `identity/sessions/refresh`, `identity/password-resets`, `identity/password-resets/complete` | anonymous | auth |
| `identity-registration` | POST `identity/accounts`, `identity/accounts/verify-email` | anonymous | auth |
| `identity-sign-out` | DELETE `identity/sessions/current` | authenticated | standard |
| `identity-account-deletion` | DELETE `identity/accounts/me` | shopper | standard |
| `identity-profile` | GET, PUT `identity/accounts/me`, `identity/accounts/me/notification-preferences` | authenticated | standard |
| `identity-addresses` | GET, POST `identity/accounts/me/addresses` | authenticated | standard |
| `identity-address` | PUT, DELETE `identity/accounts/me/addresses/{id}` | authenticated | standard |
| `identity-phone-verification` | POST `identity/accounts/me/phone-verifications`, `.../confirm` | authenticated | standard |
| `catalog-reads` | GET `catalog/products`, `catalog/products/{id}`, `catalog/categories`, `catalog/categories/{id}` | anonymous | browse |
| `catalog-image-registration` | POST `catalog/products/{id}/images` (body up to 5 MiB) | operator | operator |
| `catalog-creations` | POST `catalog/products`, `catalog/products/{id}/withdrawal`, `catalog/products/{id}/reinstatement`, `catalog/products/{id}/stock-adjustments`, `catalog/categories`, `catalog/categories/{id}/withdrawal`, `catalog/categories/{id}/reinstatement` | operator | operator |
| `catalog-updates` | PUT `catalog/products/{id}`, `catalog/categories/{id}` | operator | operator |
| `cart-merge` | POST `cart/merge` | authenticated | standard |
| `cart` | GET, DELETE `cart` | anonymous | standard |
| `cart-line-addition` | POST `cart/lines` | anonymous | standard |
| `cart-line` | PUT, DELETE `cart/lines/{id}` | anonymous | standard |
| `order-placement` | POST `orders` (never retried) | shopper | checkout |
| `order-history` | GET `orders` | shopper | standard |
| `order-read` | GET `orders/{id}` | authenticated | standard (operator tier for operators) |
| `order-cancellation` | POST `orders/{id}/cancellation` | shopper | standard |
| `order-status` | POST `orders/{id}/status` | operator | operator |
| `payment-simulator-rules` | GET `payments/simulator/rules` | operator | operator |
| `payment-reads` | GET `payments/attempts`, `payments/attempts/{id}`, `payments/refunds`, `payments/refunds/{id}` | authenticated | standard |
| `notification-failed` | GET `notifications/failed` | operator | operator |
| `notification-retry` | POST `notifications/{id}/retry` | operator | operator |
| `notification-list` | GET `notifications` | shopper | standard |
| `telemetry-traces`, `telemetry-logs` (feature 005) | POST `telemetry/v1/traces`, `telemetry/v1/logs`, rewritten to the collector's `/v1/traces`, `/v1/logs`; `Cookie` and `Authorization` removed; body up to 256 KiB; never retried | anonymous | browse |
| `storefront` (feature 005) | GET, HEAD on every path outside `/api/`, `/actuator/` and `/.well-known/` (`Path=/**` with the `NotPath` predicate), `order: 1` so it is tried after every API route | anonymous | browse |

Where `gateway-routes.md` says "shopper" but the OpenAPI operation accepts "shopper or operator" (profile,
addresses, cart merge, reading one order, payments), the route is `authenticated` and the service decides; every
service repeats the authorisation decision (FR-005).

**Tier of registration and e-mail verification (T169).** `POST identity/accounts` and
`POST identity/accounts/verify-email` use the `auth` tier, like sign-in, refresh and password resets: they are
anonymous, credential-adjacent calls (a registration chooses a password; verification redeems an e-mailed token), and
every registration sends a verification e-mail, so a per-address budget of 10 per minute limits mass registration,
mail flooding and token guessing. All of them share one budget per source address. Clients that register many
accounts from one address (the acceptance suite, the performance run) raise
`GATEWAY_RATELIMIT_REQUESTSPERMINUTE_AUTH` locally (`platform/perf/compose.perf.yml`).

Deny by default: any other method and path matches no route and answers 404 `not-found` without reaching a service.
Since feature 005 a `GET` or `HEAD` outside `/api/`, `/actuator/` and `/.well-known/` is the storefront's (the static
container answers the SPA shell or its own 404); every other method anywhere, and every `GET` under those three
prefixes (`/api/v1/unknown`, `/actuator/health`, `/.well-known/jwks.json` on port 8080), stays 404. The exclusion is
the `NotPath` route predicate (`NotPathRoutePredicateFactory`, a gateway addition: Spring path patterns the route must
not match), so the catch-all can never shadow an API path.

### Service URLs

Each route's `uri` is an environment variable with the documented default (`docs/service-conventions.md`):
`IDENTITY_URL`, `CATALOG_URL`, `CART_URL`, `ORDER_URL`, `PAYMENT_URL`, `NOTIFICATION_URL`, default
`http://localhost:8080`, Compose `http://<context>:8080`. Service names resolve to every instance through DNS, so
instances can be added or removed without changing the gateway. The upstream Netty client resolves them explicitly
for instance churn (`UpstreamResolver`, an `HttpClientCustomizer`): Reactor Netty's DNS resolver caches an answer for
at most 5 s (instead of the record's own TTL) and selects the returned addresses round-robin, the same settings as the
services' internal clients (`WebClientDefaults.httpClient` in platform-core). The image also sets
`networkaddress.cache.ttl=5` for lookups through the JDK resolver.

Pooled upstream connections live at most 30 s (`httpclient.pool.max-life-time`, idle 15 s): a replica added behind
a service name receives traffic within that time even from a client that would otherwise keep reusing one keep-alive
connection (the scale-up proof of `platform/compose/scripts/resilience.sh`, SC-008).

### Timeouts and retries

Connect timeout 2 s. Upstream response timeout per tier (route metadata `response-timeout`): auth 5 s, browse
5 s, standard 10 s, operator 15 s, checkout 30 s; a slower upstream answers 504. Retries happen only when no
connection could be opened (`java.net.ConnectException`, which includes the connect timeout, or
`java.net.NoRouteToHostException`): nothing was sent, so the request cannot have been applied. A retry opens a new
connection, which may go to another of the resolved instances; there are at most two, with a 50-500 ms back-off. The
default `Retry` filter covers GET and HEAD on every route; the identity routes that take POST (`identity-credentials`,
`identity-registration`, `identity-addresses`, `identity-phone-verification`) add a route `Retry` filter for POST with
the same exceptions (T180), so sign-ins and registrations survive the stop of one identity instance. Upstream
statuses, and failures after the connection was open, are never retried, and no other POST is retried: in particular
`POST /api/v1/orders` never is, it relies on the client's `Idempotency-Key`.

## Authentication

Spring Security reactive resource server (`SecurityConfiguration`). Every request that carries a bearer token is
authenticated, also on anonymous routes: an invalid token answers 401 and never degrades to anonymous. Tokens are
EdDSA (Ed25519) JWTs (`JWKS_URI`, `JWT_ISSUER`, `JWT_AUDIENCE`): `NimbusReactiveJwtDecoder` over the `JwksClient`
key source, with signature verification by the JDK's Ed25519 provider (`Ed25519.kt`; Nimbus's own Ed25519 classes
need Google Tink), and `exp`/`nbf` (60 s skew), `iss`, `aud`, `typ` and `sub` validated. The `roles` claim becomes
`ROLE_shopper` / `ROLE_operator` authorities.

`JwksClient` caches the JWK Set for `gateway.jwt.cache-ttl` (5 min), refreshes it once on an unknown `kid` (at most
every `refresh-cooldown`, 10 s), shares in-flight fetches and keeps the last good set when a refresh fails. When the
set cannot be fetched and no cached key matches, the request answers 503 `unavailable`; it is never accepted
unverified.

`RouteAccessFilter` applies the route's `auth` (401 `unauthorized` with `WWW-Authenticate: Bearer`, 403
`forbidden`), forwards `X-Account-Id` (`sub`) and `X-Roles` (comma-separated roles) and the bearer token, and marks
responses of authenticated calls `Cache-Control: no-store`. Client-supplied `X-Account-Id`, `X-Roles`,
`X-Internal-Token`, `Forwarded`, `X-Forwarded-*` and `X-Real-IP` are dropped before anything else runs. The caller
is the resource server's principal or, when the browser-session filter unsealed a bearer from the session cookie
(below), the `JwtAuthenticationToken` it left in the exchange attribute `BROWSER_AUTHENTICATION_ATTRIBUTE`, verified
by the same decoder and converter as a client's token.

## Rate limiting

`RateLimitFilter` charges each routed request to a token bucket per tier and client key: the source address for
`auth` (sign-in, refresh, password resets, registration, e-mail verification), otherwise the account id of an
authenticated caller or the source address. Defaults per minute: auth 10,
browse 600, standard 120, checkout 20, operator 300 (`gateway.rate-limit.requests-per-minute.<tier>`, overridable
with `GATEWAY_RATELIMIT_REQUESTSPERMINUTE_<TIER>`). Over budget: 429 `throttled` with `Retry-After` in seconds.

**MVP deviation**: buckets live in each gateway instance's memory (`InMemoryRateLimiter`), not in a shared store.
With N gateway instances a client can get up to N times its budget, and a restart resets budgets. A shared store
(for example Redis with Spring Cloud Gateway's `RequestRateLimiter`) replaces it when the gateway is scaled out.

## Correlation, headers, limits and errors

- `EdgeHttpHandlerDecorator` wraps the whole public handler: `X-Correlation-Id` is accepted when it is a UUID or
  16-64 characters of `[A-Za-z0-9-]`, otherwise replaced by a new UUID (the refused value is logged as
  `originalCorrelationId`); it is forwarded upstream, echoed on every response and kept in the MDC. One ECS access
  log line per request (`method`, `path`, `status`, `durationMs`, plus `traceId` and `spanId`).
- The access line is written when the response is complete, after `HttpWebHandlerAdapter` has stopped the
  `http.server.requests` observation and outside its scope, so the MDC holds no trace there. The decorator puts a
  `RequestTrace` into the Reactor context, `TraceCaptureWebFilter` (the first web filter, inside the observation)
  copies the server span's `traceId`/`spanId` from the exchange's `ServerRequestObservationContext` into it, and the
  line is written with those ids in the MDC (ECS console line) and the span context current (trace context of the OTLP
  log record, which the collector turns into `traceId` for Loki). The server span continues an incoming
  `traceparent`, and the upstream call is its child, so the access line links to the same trace as the services'
  lines (FR-025).
- `X-Forwarded-Proto: https` (a TLS terminator in front of the gateway) is kept as the request scheme before the
  header itself is dropped, so the browser-session filters choose the `__Host-` cookie names ("Cookie names by
  transport" below) and the `Origin` check compares against `https`.
- Every response carries `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'` and
  `Referrer-Policy: no-referrer`; `Server`, `X-Powered-By` and internal headers are removed. The `storefront` route
  replaces the CSP with the page policy and adds `Permissions-Policy` ("Storefront route and per-route CSP" below).
- Request bodies above 1 MiB (5 MiB on the image route) answer 413 before reaching a service; request headers are
  limited to 16 KB and idle connections to 60 s.
- `ProblemWebExceptionHandler` renders every gateway-originated error as `application/problem+json`
  (`type`, `title`, `status`, `detail`, `instance`, `correlationId`): 400 `validation`, 401 `unauthorized`, 403
  `forbidden`, 404 `not-found`, 413 `payload-too-large`, 429 `throttled`, 500 `internal`, 502/503/504
  `unavailable`. The slugs `payload-too-large` and `internal` are gateway additions to the list of
  `docs/service-conventions.md`. Upstream responses, errors included, pass through unchanged.

## Browser session (storefront)

Feature 005 (`specs/005-storefront-dev-bootstrap/contracts/openapi/gateway-browser-session.yaml`, data-model.md
section 4): the storefront never holds a token. Package `com.ecommerce.gateway.browser`:

- `SealedSession` is the pure payload (access and refresh token, account id, roles, `lastSeenAt`, `issuedAt`);
  `AesGcmSessionSealer` seals it with the JDK's AES-256-GCM: a fresh 12-byte nonce per sealing, the key id bound as
  associated data, wire format `<keyId>.<Base64url(nonce || ciphertext || tag)>` without padding, a key set for
  rotation (today one key, id `k1`). Unseal failures of any kind are `null` and never logged; `toString()` of the
  payload shows no token.
- `BrowserSessionKeys` reads `gateway.browser-session.key` (`BROWSER_SESSION_KEY`, 32 random bytes, Base64, shared by
  every replica). Outside the `dev` and `test` profiles a missing key, or one that does not decode to exactly
  32 bytes, stops the start-up with the fix in the message, like identity's `IDENTITY_SIGNING_KEY`; under those
  profiles a throw-away key is generated per process (`BrowserSessionConfiguration`).
- `BrowserSessionRules` is the decision table of data-model.md section 4.3, pure and property-tested
  (`BrowserSessionRulesSpec`): no cookie passes through unchanged (API clients keep bearer tokens); cookie plus
  `Authorization` is 400 `validation`; a non-GET with the cookie needs `Sec-Fetch-Site` `same-origin` or `none`
  and, when present, an `Origin` equal to the request's scheme, host and port, otherwise 403 `forbidden` (without
  `Sec-Fetch-Site` the check fails closed unless a matching `Origin` is present); both cookie names at once, an
  unsealable cookie or `lastSeenAt` older than 30 minutes (`idle-timeout`) is 401 `unauthorized` with the cookie
  deleted; otherwise the session is valid and refreshed first when the access token expires within 60 s
  (`refresh-ahead`).
- `BrowserSessionFilter` (global filter, order -400, before `RouteAccessFilter`) applies the table per route kind:
  ignored on `identity-registration`, the password-reset paths, `storefront` and `telemetry-*`; on sign-in an
  existing cookie is ignored; on `POST /api/v1/identity/sessions/refresh` with `X-Browser-Session: cookie` the body
  sent to identity is built from the sealed refresh token (the page never holds one). A valid session is refreshed
  through `IdentityRefreshClient` (non-blocking `WebClient` to `IDENTITY_URL`; a refused refresh ends the session
  with 401 and deletion, an unreachable identity answers 503 and keeps the cookie), then verified by
  `BearerAuthenticator` (the resource server's decoder and `roles` converter; an invalid token is 401 with deletion,
  unreadable keys 503) and injected as `Authorization: Bearer`. On the way back (`SessionCookieActions`, at commit)
  the cookie is re-set with a new `lastSeenAt` (silent renewal), deleted on sign-out and on an upstream 401, and
  every such response is `Cache-Control: no-store`. Sign-out with an unusable cookie answers 204 with the deletion
  without reaching identity.
- `SessionSummaryResponse` rewrites identity's 200 token pair of a browser-mode sign-in or refresh into
  `{ "expiresAt": <lastSeenAt + idle-timeout>, "roles": [...] }` and seals the tokens into the cookie instead;
  identity is asked for an uncompressed body (`Accept-Encoding` dropped) because the gateway reads it. A token pair
  that cannot be read, or a session over the 4 KiB cookie budget, fails closed with 502 `unavailable` and no cookie.
  Without `X-Browser-Session: cookie` identity's body passes through unchanged and no cookie is set.
- Refusals are platform `Problem` answers (`BrowserProblems`) with `X-Correlation-Id`, `Cache-Control: no-store` and,
  for 401, the deletion `Set-Cookie` (`GatewayProblemException.cookies`).

## Cart cookie

`CartCookieFilter` (order -350, after the session filter so a merge carries the injected bearer) acts only when the
request carries `X-Browser-Session: cookie`: the sealed cart cookie is unsealed and injected as `X-Cart-Token` when
the client sent none (an explicit header wins; an unsealable cookie, or both names at once, is deleted and ignored);
an upstream `X-Cart-Token` is sealed into the cart cookie (`HttpOnly; SameSite=Lax; Path=/; Max-Age=2592000`) and
removed from the response; a 200 from `POST /api/v1/cart/merge` deletes the cookie, every other status keeps it. A
token that would not fit the cookie budget is passed through as the header instead. Without the header the cart
contract of feature 004 applies untouched.

## Storefront route and per-route CSP

The `storefront` route (`STOREFRONT_URL`, default `http://localhost:8082`, Compose `http://storefront:8080`) forwards
every `GET`/`HEAD` outside `/api/`, `/actuator/` and `/.well-known/` to the static container and passes its cache
headers through (`no-store` on the shell, `immutable` on hashed assets): no `Cache-Control: no-store` is added on this
anonymous route and no cookie is read or set. `EdgeHeaders.policyFor(routeId)` gives the route its page policy,
applied by `RouteHeadersFilter` as a before-commit action after the edge decorator's hardening, so it replaces the API
CSP on this route only:
`default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: https:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'`
plus `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()`; `X-Frame-Options`, `Referrer-Policy`,
`Strict-Transport-Security` and `X-Content-Type-Options` are unchanged. Every `/api/**` response and every gateway
error keeps the strict API policy (`EdgeHeadersTest`, `StorefrontRouteIT`).

## Telemetry routes

`telemetry-traces` and `telemetry-logs` forward the storefront's OTLP/HTTP JSON (`POST /api/v1/telemetry/v1/traces`
and `/logs`) to the collector's receiver at `OTEL_COLLECTOR_URL` (default `http://localhost:4318`, Compose
`http://otel-collector:4318`) with the prefix rewritten (`RewritePath`), `Cookie` and `Authorization` removed
(`RemoveRequestHeader`), tier `browse` per source address, `max-body-size: 256KB` (`RequestSizeFilter` honours a route
limit below the 1 MiB default as well as above it: 413 `payload-too-large`), no `Retry` filter (a collector that is not
running answers 503 `unavailable` and the browser drops the batch), and the collector's own 200 or 4xx body passed
through. `GET` or any other method on these paths matches no route (404).

## Cookie names by transport

`__Host-` prefixed cookies require `Secure` and HTTPS, so `Transport.of(scheme, forwardedProto)` picks the names per
request: `__Host-session` and `__Host-cart` with `Secure` when the request arrived over HTTPS (scheme `https`, or
`X-Forwarded-Proto: https`, kept as the request scheme by the edge decorator), `session` and `cart` without `Secure`
over plain HTTP such as `http://localhost`. All other attributes are identical (session: `HttpOnly; SameSite=Strict;
Path=/`, no `Max-Age`; cart: `HttpOnly; SameSite=Lax; Path=/; Max-Age=2592000`; never a `Domain`). `BrowserCookies`
renders the `Set-Cookie` values in that documented order and deletions as `<name>=; Max-Age=0; <same attributes>`. The
gateway accepts whichever name is present and never sets both: setting one name deletes the other in the same
response, and a request carrying both names of the session cookie is 401 `unauthorized` with both deleted (both
cart names: ignored and both deleted).

## Observability

Management port 8081: `/actuator/health` (with `/liveness` and `/readiness`) and `/actuator/prometheus`.
`http.server.requests` is published with histogram buckets
(`management.metrics.distribution.percentiles-histogram.http.server.requests`), so `http_server_requests_seconds_bucket`
feeds the p95 of the Service RED dashboard like the services' series (FR-026). Logs are
ECS JSON on the console. Traces, logs and metrics are exported over OTLP/HTTP to
`${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}` (`management.opentelemetry.tracing.export.otlp.endpoint`,
`management.opentelemetry.logging.export.otlp.endpoint`, `management.otlp.metrics.export.url`).

## Tests

```bash
./gradlew -q :services:gateway:test             # route policies, filters, token bucket, correlation, problems, JWT,
                                                # sealing and session rules (SealedSessionSpec, BrowserSessionRulesSpec)
./gradlew -q :services:gateway:integrationTest  # GatewayRoutingIT, GatewayRetryIT, JwksClientIT, JwksUnavailableIT,
                                                # ManagementPortIT, BrowserSessionIT, CartCookieIT, StorefrontRouteIT
./gradlew -q :services:gateway:contractTest     # Pact consumer gateway -> identity (JWKS): build/pacts/gateway-identity.json
./gradlew -q :services:gateway:contractVerify   # Pact provider: storefront-gateway.json (StorefrontGatewayProviderIT,
                                                # skipped with a message until the storefront consumer has written it)
./gradlew -q :services:gateway:pitest           # mutation testing of the unit layer (part of check)
```

Mutation testing (T146, Principle VIII): the module applies the `pitest` convention with target
`com.ecommerce.gateway.*` and the 80 % threshold. The unit layer drives the filters with `MockServerWebExchange`,
the edge decorator with a stub `HttpHandler`, and the JWT decoder with keys generated per test. Excluded in
`services/gateway/build.gradle.kts`, because they are Spring wiring or I/O adapters covered by the integration and
contract layers: `GatewayApplication`, `SecurityConfiguration`, `OpenTelemetryAppenderInstaller`, `JwksClient`
(`JwksClientIT`, `JwksUnavailableIT`, `IdentityJwksPactTest`), `BrowserSessionConfiguration` and
`IdentityRefreshClient` (`BrowserSessionIT`).

The provider verification of the storefront pact (`StorefrontProviderStates`) boots the gateway in front of one
WireMock for identity (with the JWKS of the RFC 8037 test key), cart and storefront and a second one, on a reserved
port, for the collector (stopped by the state "the telemetry collector is not running"), with a fixed
`BROWSER_SESSION_KEY`. The sealed cookie values only the provider can mint reach the replayed requests in two ways:
as provider-state values `sessionCookie` and `cartCookie` (for `fromProviderState` generators in the consumer pact)
and by rewriting any `session`/`__Host-session` or `cart`/`__Host-cart` value of the request's `Cookie` header.

The integration tests use WireMock upstreams and a WireMock JWKS with an Ed25519 key generated per run. The Pact
test signs its token with the RFC 8037 test key, whose public half is the first JWKS example of
`contracts/internal/pact-interactions.md`. The gateway has no `acceptanceTest` sources: the cross-service journeys
live in `:acceptance`.
