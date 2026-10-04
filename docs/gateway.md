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

Deny by default: any other method and path, including `/internal/**`, `/.well-known/**` and `/actuator/**` on
port 8080, matches no route and answers 404 `not-found` without reaching a service.

### Service URLs

Each route's `uri` is an environment variable with the documented default (`docs/service-conventions.md`):
`IDENTITY_URL`, `CATALOG_URL`, `CART_URL`, `ORDER_URL`, `PAYMENT_URL`, `NOTIFICATION_URL`, default
`http://localhost:8080`, Compose `http://<context>:8080`. Service names resolve to every instance through DNS, so
instances can be added or removed without changing the gateway. The upstream Netty client resolves them explicitly
for instance churn (`UpstreamResolver`, an `HttpClientCustomizer`): Reactor Netty's DNS resolver caches an answer for
at most 5 s (instead of the record's own TTL) and selects the returned addresses round-robin, the same settings as the
services' internal clients (`WebClientDefaults.httpClient` in platform-core). The image also sets
`networkaddress.cache.ttl=5` for lookups through the JDK resolver.

### Timeouts and retries

Connect timeout 2 s. Upstream response timeout per tier (route metadata `response-timeout`): auth 5 s, browse
5 s, standard 10 s, operator 15 s, checkout 30 s; a slower upstream answers 504. The default `Retry` filter retries
GET and HEAD only, at most twice with a 50-500 ms back-off, and only on `java.net.ConnectException` (no connection
could be opened); a retry opens a new connection, which may go to another of the resolved instances.
Upstream statuses are never retried, and no other method is, so `POST /api/v1/orders` is never retried.

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

Management port 8081: `/actuator/health` (with `/liveness` and `/readiness`) and `/actuator/prometheus`.
`http.server.requests` is published with histogram buckets
(`management.metrics.distribution.percentiles-histogram.http.server.requests`), so `http_server_requests_seconds_bucket`
feeds the p95 of the Service RED dashboard like the services' series (FR-026). Logs are
ECS JSON on the console. Traces, logs and metrics are exported over OTLP/HTTP to
`${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}` (`management.opentelemetry.tracing.export.otlp.endpoint`,
`management.opentelemetry.logging.export.otlp.endpoint`, `management.otlp.metrics.export.url`).

## Tests

```bash
./gradlew -q :services:gateway:test             # route policies, filters, token bucket, correlation, problems, JWT, ...
./gradlew -q :services:gateway:integrationTest  # GatewayRoutingIT, JwksClientIT, JwksUnavailableIT, ManagementPortIT
./gradlew -q :services:gateway:contractTest     # Pact consumer gateway -> identity (JWKS): build/pacts/gateway-identity.json
./gradlew -q :services:gateway:pitest           # mutation testing of the unit layer (part of check)
```

Mutation testing (T146, Principle VIII): the module applies the `pitest` convention with target
`com.ecommerce.gateway.*` and the 80 % threshold. The unit layer drives the filters with `MockServerWebExchange`,
the edge decorator with a stub `HttpHandler`, and the JWT decoder with keys generated per test. Excluded in
`services/gateway/build.gradle.kts`, because they are Spring wiring or I/O adapters covered by the integration and
contract layers: `GatewayApplication`, `SecurityConfiguration`, `OpenTelemetryAppenderInstaller` and `JwksClient`
(`JwksClientIT`, `JwksUnavailableIT`, `IdentityJwksPactTest`).

The integration tests use WireMock upstreams and a WireMock JWKS with an Ed25519 key generated per run. The Pact
test signs its token with the RFC 8037 test key, whose public half is the first JWKS example of
`contracts/internal/pact-interactions.md`. The gateway has no `acceptanceTest` sources: the cross-service journeys
live in `:acceptance`.
