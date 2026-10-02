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
| `identity-registration` | POST `identity/accounts`, `identity/accounts/verify-email` | anonymous | standard |
| `identity-sign-out` | DELETE `identity/sessions/current` | authenticated | standard |
| `identity-account-deletion` | DELETE `identity/accounts/me` | shopper | standard |
| `identity-profile` | GET, PUT `identity/accounts/me`, `identity/accounts/me/notification-preferences` | authenticated | standard |
| `identity-addresses` | GET, POST `identity/accounts/me/addresses` | authenticated | standard |
| `identity-address` | PUT, DELETE `identity/accounts/me/addresses/{id}` | authenticated | standard |
| `identity-phone-verification` | POST `identity/accounts/me/phone-verifications`, `.../confirm` | authenticated | standard |
| `catalog-reads` | GET `catalog/products`, `catalog/products/{id}`, `catalog/categories`, `catalog/categories/{id}` | anonymous | browse |
| `catalog-image-registration` | POST `catalog/products/{id}/images` (body up to 5 MiB) | operator | operator |
| `catalog-creations` | POST `catalog/products`, `catalog/products/{id}/withdrawal`, `catalog/products/{id}/stock-adjustments`, `catalog/categories` | operator | operator |
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

Where `gateway-routes.md` says "shopper" but the OpenAPI operation accepts "shopper or operator" (profile,
addresses, cart merge, reading one order, payments), the route is `authenticated` and the service decides; every
service repeats the authorisation decision (FR-005). Register and verify-email use `standard`; the `auth` tier covers
sessions and password resets (T058).

Deny by default: any other method and path, including `/internal/**`, `/.well-known/**` and `/actuator/**` on
port 8080, matches no route and answers 404 `not-found` without reaching a service.

### Service URLs

Each route's `uri` is an environment variable with the documented default (`docs/service-conventions.md`):
`IDENTITY_URL`, `CATALOG_URL`, `CART_URL`, `ORDER_URL`, `PAYMENT_URL`, `NOTIFICATION_URL`, default
`http://localhost:8080`, Compose `http://<context>:8080`. Service names resolve to every instance through DNS; the
image sets `networkaddress.cache.ttl=5`, so instances can be added or removed without changing the gateway.

### Timeouts and retries

Connect timeout 2 s. Upstream response timeout per tier (route metadata `response-timeout`): auth 5 s, browse
5 s, standard 10 s, operator 15 s, checkout 30 s; a slower upstream answers 504. The default `Retry` filter retries
GET and HEAD only, at most twice with a 50-500 ms back-off, and only on `java.net.ConnectException` (no connection
could be opened); upstream statuses are never retried, so `POST /api/v1/orders` is never retried.

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
`X-Internal-Token`, `Forwarded`, `X-Forwarded-*` and `X-Real-IP` are dropped before anything else runs.

## Rate limiting

`RateLimitFilter` charges each routed request to a token bucket per tier and client key: the source address for
`auth`, otherwise the account id of an authenticated caller or the source address. Defaults per minute: auth 10,
browse 600, standard 120, checkout 20, operator 300 (`gateway.rate-limit.requests-per-minute.<tier>`, overridable
with `GATEWAY_RATELIMIT_REQUESTSPERMINUTE_<TIER>`). Over budget: 429 `throttled` with `Retry-After` in seconds.

**MVP deviation**: buckets live in each gateway instance's memory (`InMemoryRateLimiter`), not in a shared store.
With N gateway instances a client can get up to N times its budget, and a restart resets budgets. A shared store
(for example Redis with Spring Cloud Gateway's `RequestRateLimiter`) replaces it when the gateway is scaled out.

## Correlation, headers, limits and errors

- `EdgeHttpHandlerDecorator` wraps the whole public handler: `X-Correlation-Id` is accepted when it is a UUID or
  16-64 characters of `[A-Za-z0-9-]`, otherwise replaced by a new UUID (the refused value is logged as
  `originalCorrelationId`); it is forwarded upstream, echoed on every response and kept in the MDC. One ECS access
  log line per request (`method`, `path`, `status`, `durationMs`).
- Every response carries `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'` and
  `Referrer-Policy: no-referrer`; `Server`, `X-Powered-By` and internal headers are removed.
- Request bodies above 1 MiB (5 MiB on the image route) answer 413 before reaching a service; request headers are
  limited to 16 KB and idle connections to 60 s.
- `ProblemWebExceptionHandler` renders every gateway-originated error as `application/problem+json`
  (`type`, `title`, `status`, `detail`, `instance`, `correlationId`): 400 `validation`, 401 `unauthorized`, 403
  `forbidden`, 404 `not-found`, 413 `payload-too-large`, 429 `throttled`, 500 `internal`, 502/503/504
  `unavailable`. The slugs `payload-too-large` and `internal` are gateway additions to the list of
  `docs/service-conventions.md`. Upstream responses, errors included, pass through unchanged.

## Observability

Management port 8081: `/actuator/health` (with `/liveness` and `/readiness`) and `/actuator/prometheus`. Logs are
ECS JSON on the console. Traces, logs and metrics are exported over OTLP/HTTP to
`${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}` (`management.opentelemetry.tracing.export.otlp.endpoint`,
`management.opentelemetry.logging.export.otlp.endpoint`, `management.otlp.metrics.export.url`).

## Tests

```bash
./gradlew -q :services:gateway:test             # correlation rules, token bucket, route table vs OpenAPI
./gradlew -q :services:gateway:integrationTest  # GatewayRoutingIT, JwksClientIT, JwksUnavailableIT, ManagementPortIT
./gradlew -q :services:gateway:contractTest     # Pact consumer gateway -> identity (JWKS): build/pacts/gateway-identity.json
```

The integration tests use WireMock upstreams and a WireMock JWKS with an Ed25519 key generated per run. The Pact
test signs its token with the RFC 8037 test key, whose public half is the first JWKS example of
`contracts/internal/pact-interactions.md`. The gateway has no `acceptanceTest` sources: the cross-service journeys
live in `:acceptance`.
