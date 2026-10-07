# The web storefront

The storefront is the browser application of the platform (feature 005): a TypeScript + React single-page
application built with Vite, served by the `storefront` container (nginx, unprivileged) behind the gateway at the
same origin as the API. Shoppers browse, keep a cart, register, sign in, check out and follow their orders; operators
get a minimal console (order fulfilment and stock adjustments). Specification and design:
[`specs/005-storefront-dev-bootstrap/`](../specs/005-storefront-dev-bootstrap/) (spec, plan, research, data model,
contracts); package conventions: [`frontend/README.md`](../frontend/README.md).

## How it reaches the platform

| Concern | Where it lives | Rule |
|---------|----------------|------|
| Serving the bundle | `platform/docker/Dockerfile.storefront`, `platform/docker/storefront/nginx.conf`, Compose service `storefront` | hashed assets immutable, `index.html` `no-store`; the gateway's `storefront` route forwards every `GET`/`HEAD` outside `/api/**`, `/actuator/**`, `/.well-known/**` and applies the storefront CSP ([docs/gateway.md](gateway.md)) |
| Sign-in and session | gateway `browser/` package (sealed HttpOnly cookie `session`, `__Host-session` over HTTPS) | the page never sees a token; 30-minute idle expiry, silent renewal; the storefront sends `X-Browser-Session: cookie` on every request |
| Anonymous cart | gateway `CartCookieFilter` (cookie `cart`, 30 days) | the storefront never stores the cart token |
| Typed API client | `frontend/src/api/generated/` generated from the OpenAPI contracts by `npm run generate` | hand-written response types are forbidden (constitution VII) |
| Telemetry | `frontend/src/telemetry/`, gateway routes `telemetry-traces`/`telemetry-logs`, collector processors in `platform/observability/otel-collector.yaml` | OpenTelemetry web SDK; attribute allow-list in the exporter, redaction in the collector; dashboard "Storefront RUM" |

## Working on it

- Start everything: `scripts/dev-env.sh init --start` ([docs/dev-environment.md](dev-environment.md)); the storefront
  answers at `http://localhost:${GATEWAY_PORT:-8080}/`.
- Develop with hot reload: `npm --prefix frontend run dev` proxies `/api` to the local gateway (`GATEWAY_PORT`).
- Quality gate: `./gradlew -q verify` runs `npm run lint` and `npm run test`; `npm run mutate` (Stryker, 80 % floor),
  `npm run pact` (consumer pacts into `build/pacts/storefront-*.json`, verified by the providers with
  `./gradlew -q contractVerify`), `npm run acceptance` (Cucumber.js + Playwright with an axe audit, needs
  `STOREFRONT_URL`; `STOREFRONT_VIEWPORT=mobile` runs at 360 px). Against a shared stack start the gateway with the
  rate-limit override `platform/perf/compose.perf.yml`, as the platform workflow does.
- Pipelines: `.github/workflows/storefront.yml` ([docs/ci-cd.md](ci-cd.md)); the platform workflow runs the storefront
  acceptance suites after the JVM suite.

## Telemetry in one paragraph

Every request carries `X-Correlation-Id` and the W3C `traceparent`, so a browser span, the gateway access line and
the services' log lines join on both ids in Grafana ("Requests by correlation id", "Storefront RUM"). The exporter
posts OTLP/HTTP JSON to `/api/v1/telemetry/v1/{traces,logs}` through the gateway (anonymous, `browse` tier, 256 KiB,
cookies stripped) with `credentials: 'omit'`; it keeps only route templates, HTTP method and status, element role and
id, durations, error class names, the correlation id and a random session id, drops a batch on any failure, pauses on
429 for `Retry-After`, and never retries in a loop. The collector removes URL, user and header attributes and redacts
e-mail, token and cookie-looking values for `service.name=storefront` as a second layer. When the observability
profile is down the gateway answers 503 and the page keeps working.
