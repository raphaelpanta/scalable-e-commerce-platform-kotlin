# Pact interactions (feature 004)

Single reference for consumer and provider implementers of every Pact contract of the platform. Consumers write
their contract tests with exactly the descriptions, provider states and request/response shapes below; providers
implement exactly these provider states and verify the resulting pact files. Rules: `docs/service-conventions.md`
section 6. Matrix of who talks to whom: `specs/004-ecommerce-platform-mvp/contracts/pact-matrix.md`. Schemas of the
HTTP bodies: `contracts/internal/*-internal.yaml` (and `contracts/openapi/*.yaml` for the public ones); schemas of
the events: `contracts/asyncapi/events.yaml`. When a table and an OpenAPI or AsyncAPI file disagree, fix the table
and the file in the same change.

## 1. Rules that apply to every interaction

- **Pact format**: Pact specification V4, JVM `au.com.dius.pact` 4.x. Consumer name = the consuming service
  (`gateway`, `cart`, `order`, `notification`, plus `platform-probe` for the health pact); provider name = the
  providing service (`identity`, `catalog`, `cart`, `payment`, `order`). Files go to `<repo>/build/pacts`.
- **Interaction description**: the string in the first column, character for character. Descriptions are unique within
  a consumer/provider pair.
- **Provider state**: written as `name {json}`. The text before the JSON is the Pact provider-state **name**
  (`.given("a product exists", params)`); the JSON object is the state **parameters** (Pact V3/V4 state parameters;
  values may be nested arrays or objects). A state without a JSON object has no parameters. A provider implements each state
  name once, creates exactly the data the parameters describe (all other data is irrelevant to the interaction) and
  never depends on another service. States repeated in one row are separate `.given` calls on the same interaction.
- **Currency** is `BRL` everywhere. A product state with `priceMinor` and `currency` means an `active` product with that
  price; `available` is `onHand - reserved`; `a product is withdrawn` is the same product with `saleState` withdrawn.
- **Common request headers** (not repeated in the rows): `Accept: application/json`; `Content-Type: application/json`
  when a body is sent; `X-Internal-Token: pact-internal-token` on every `/internal/**` request (not on the JWKS
  request); `X-Correlation-Id: 3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13` (matched by regex `^[A-Za-z0-9-]{1,64}$`). The token value is a
  test-only constant: consumer tests and provider verification both configure `INTERNAL_API_TOKEN=pact-internal-token`
  in their test setup. It is never a real secret and no production default exists.
- **Common response headers** (not repeated): `Content-Type` matched by regex `^application/json(;.*)?$` (error
  responses: `^application/problem\+json(;.*)?$`); `X-Correlation-Id` echoing the request value (regex
  `^[A-Za-z0-9-]{1,64}$`). 204 responses have no body and no `Content-Type`.
- **Errors** are RFC 9457 problems. In every error row `type`, `title` and `status` are exact; `detail` and
  `correlationId` (and `instance` when shown) are matched as strings; extension members are exact where shown.
- **Matching**: in the "matched by type" column, `uuid` = the UUID regex, `timestamp` = ISO-8601 UTC with `Z`,
  `string` = non-empty string of the shown example. Every field not listed there is matched exactly and, where the
  provider cannot control it, comes from the provider-state parameters.
- **Minimal pacts**: a consumer pact contains only the fields its code reads; providers may add fields (additive rule).
  Consumers must ignore unknown fields.
- **Absent fields** shown as absent (for example `providerReference` while pending) are absent, not `null`.

### Fixed example identifiers

All examples below use these values so that pacts and provider fixtures are reproducible.

| Meaning | Value |
|---|---|
| Shopper Ada (email only) `accountId` | `7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d` |
| Shopper Grace (SMS opted in) | `3b5d7f91-2c4e-4a68-9b0d-1f3a5c7e9b24` |
| Another shopper (owner of `addressId` `6a1d2b63-4b54-4b64-8a69-8b2e9c0e3d22`) | `9e8d7c6b-5a49-4382-9f1e-0d2c4b6a8e10` |
| Anonymised account, pseudonym `anon-4f9c2d71` | `c8a6e4d2-0b9f-4c71-8a35-6e2d4f8b1c07` |
| Unknown account | `f0e1d2c3-b4a5-4968-8776-5a4b3c2d1e0f` |
| Operator | `e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22` |
| Orders: paid / pending / declined / reserve-refused | `0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10` / `0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a11` / `0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12` / `0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a13` |
| Order numbers | `ORD-20261002-0001`, `-0002`, `-0003` (orders 1 to 3) |
| Products: Espresso Machine (`ESP-MACH-01`, 14900) | `9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01` |
| Coffee Beans 1kg (`CB-1KG`, 2450) | `3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02` |
| Trail Running Shoes (`TRS-001`, 9490) | `0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21` |
| Ceramic Mug (`MUG-CER-01`, 3290) | `b7e1c4d2-3f58-4a96-8d20-5c1e9a7b3d44` |
| Vintage Kettle (`KTL-VINT-01`, 25900) | `a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d` |
| Unknown product | `d4c3b2a1-9f8e-4d7c-8b6a-5f4e3d2c1b0a` |
| Cart; cart lines | `8d3b7a10-4c52-4e96-b1a7-2f5e9c0d6b34`; `c1a9e2f4-6b07-4d3a-8e51-0b7d4a9c2f18`, `e2b0f3a5-7c18-4e4b-9f62-1c8e5b0d3a29` |
| Addresses: owned / of another account / unknown | `5f0c1a52-3a43-4a53-9f58-7a1d8b9d2c11` / `6a1d2b63-4b54-4b64-8a69-8b2e9c0e3d22` / `7b2e3c74-5c65-4c75-9b7a-9c3f0d1f4e33` |
| Reservation (order 1) | `4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15` |
| Charge attempts (paymentId): order 1 approved / order 2 pending / order 3 declined | `c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50` / `d3e4f5a6-b7c8-4d9e-8f0a-1b2c3d4e5f60` / `8e0d1c2b-3a4f-4b5c-9d6e-7f8a9b0c1d2e` |
| Idempotency keys: order 1 / order 2 / order 3 / refund | `6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f` / `3c4d5e6f-7081-4b92-a3c4-d5e6f7081b92` / `2b3c4d5e-6f70-4a81-92b3-c4d5e6f70a81` / `9c8b7a69-5847-4362-9d1e-0f1a2b3c4d5e` |
| Refund | `4d5e6f70-8192-4a3b-8c4d-5e6f70819203` |
| Correlation id | `3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13` |
| Event ids | `ee0000NN-0000-4000-8000-0000000000NN`, NN = 01 to 15 as in section 3 |

## 2. HTTP interactions


### 2.1 gateway to identity (JWKS) (consumer `gateway`, provider `identity`)

Contract: `identity-internal.yaml` `GET /.well-known/jwks.json` (no `X-Internal-Token`). Verification owner: identity.

| Description | Provider state(s) | Request | Expected response | Matched by type |
|---|---|---|---|---|
| `a request for the JWKS document` | `identity has an active signing key {"kid":"2026-10-a1"}` | `GET /.well-known/jwks.json`<br>no `X-Internal-Token` | `200`<br>body `{"keys":[{"kty":"OKP","crv":"Ed25519","kid":"2026-10-a1","x":"11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo","use":"sig","alg":"EdDSA"}]}` | `keys[*].x` regex `^[A-Za-z0-9_-]{43}$`; `keys` array with at least 1 element; `kty`, `crv`, `kid`, `use`, `alg` exact |
| `a request for the JWKS document after a key rotation` | `identity has rotated its signing key {"previousKid":"2026-10-a1","kid":"2026-10-b2"}` | `GET /.well-known/jwks.json`<br>no `X-Internal-Token` | `200`<br>body `{"keys":[{"kty":"OKP","crv":"Ed25519","kid":"2026-10-b2","x":"xTLzCYrKsV7o344bBsBdQz1P-GDGXTbhGCw2GTt2rEA","use":"sig","alg":"EdDSA"},{"kty":"OKP","crv":"Ed25519","kid":"2026-10-a1","x":"11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo","use":"sig","alg":"EdDSA"}]}` | `keys[*].x` regex `^[A-Za-z0-9_-]{43}$`; `keys` array with at least 2 elements, the new key first; `kid` values exact |
| `a request for the JWKS document while no signing key is available` | `identity has no signing key available` | `GET /.well-known/jwks.json`<br>no `X-Internal-Token` | `503`<br>`Content-Type: application/problem+json`<br>body `{"type":"https://ecommerce.example/problems/unavailable","title":"Service unavailable","status":503,"detail":"No signing key is available.","correlationId":"3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"}` | `type`, `title`, `status` exact; `detail` and `correlationId` string |

### 2.2 order to cart (consumer `order`, provider `cart`)

Contract: `cart-internal.yaml`. Verification owner: cart.

| Description | Provider state(s) | Request | Expected response | Matched by type |
|---|---|---|---|---|
| `a request for the cart of an account` | `an account cart exists {"accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","cartId":"8d3b7a10-4c52-4e96-b1a7-2f5e9c0d6b34","lines":[{"lineId":"c1a9e2f4-6b07-4d3a-8e51-0b7d4a9c2f18","productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","sku":"ESP-MACH-01","name":"Espresso Machine","quantity":1,"priceAtAdd":{"amountMinor":14900,"currency":"BRL"}},{"lineId":"e2b0f3a5-7c18-4e4b-9f62-1c8e5b0d3a29","productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","sku":"CB-1KG","name":"Coffee Beans 1kg","quantity":2,"priceAtAdd":{"amountMinor":2450,"currency":"BRL"}}]}` | `GET /internal/carts/by-account/7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d` | `200`<br>body `{"cartId":"8d3b7a10-4c52-4e96-b1a7-2f5e9c0d6b34","revision":"rev-7f3a9c21","lines":[{"lineId":"c1a9e2f4-6b07-4d3a-8e51-0b7d4a9c2f18","productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","sku":"ESP-MACH-01","name":"Espresso Machine","quantity":1,"priceAtAdd":{"amountMinor":14900,"currency":"BRL"}},{"lineId":"e2b0f3a5-7c18-4e4b-9f62-1c8e5b0d3a29","productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","sku":"CB-1KG","name":"Coffee Beans 1kg","quantity":2,"priceAtAdd":{"amountMinor":2450,"currency":"BRL"}}],"total":{"amountMinor":19800,"currency":"BRL"}}` | `revision` string (min length 1, example `rev-7f3a9c21`); everything else exact |
| `a request for the cart of an account with an empty cart` | `an empty account cart exists {"accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","cartId":"8d3b7a10-4c52-4e96-b1a7-2f5e9c0d6b34"}` | `GET /internal/carts/by-account/7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d` | `200`<br>body `{"cartId":"8d3b7a10-4c52-4e96-b1a7-2f5e9c0d6b34","revision":"rev-0c11d2e3","lines":[],"total":{"amountMinor":0,"currency":"BRL"}}` | `revision` string (min length 1); everything else exact |
| `a request for the cart of an account that has no cart` | `no cart exists for the account {"accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"}` | `GET /internal/carts/by-account/7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d` | `404`<br>`Content-Type: application/problem+json`<br>body `{"type":"https://ecommerce.example/problems/not-found","title":"Not found","status":404,"detail":"No cart exists for the account.","correlationId":"3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"}` | `type`, `title`, `status` exact; `detail` and `correlationId` string |
| `a request to clear the cart of an account` | `an account cart exists {"accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","cartId":"8d3b7a10-4c52-4e96-b1a7-2f5e9c0d6b34","lines":[{"lineId":"c1a9e2f4-6b07-4d3a-8e51-0b7d4a9c2f18","productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","sku":"ESP-MACH-01","name":"Espresso Machine","quantity":1,"priceAtAdd":{"amountMinor":14900,"currency":"BRL"}},{"lineId":"e2b0f3a5-7c18-4e4b-9f62-1c8e5b0d3a29","productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","sku":"CB-1KG","name":"Coffee Beans 1kg","quantity":2,"priceAtAdd":{"amountMinor":2450,"currency":"BRL"}}]}` | `POST /internal/carts/by-account/7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d/clear` | `204`<br>no body | none (no body) |
| `a request to clear the cart of an account that has no cart` | `no cart exists for the account {"accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"}` | `POST /internal/carts/by-account/7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d/clear` | `204`<br>no body | none (no body) |

### 2.3 order to catalog (consumer `order`, provider `catalog`)

Contract: `catalog-internal.yaml`. Verification owner: catalog.

| Description | Provider state(s) | Request | Expected response | Matched by type |
|---|---|---|---|---|
| `a request to reserve stock for an order` | `a product exists {"productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","sku":"ESP-MACH-01","name":"Espresso Machine","priceMinor":14900,"currency":"BRL","available":5}`<br>`a product exists {"productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","sku":"CB-1KG","name":"Coffee Beans 1kg","priceMinor":2450,"currency":"BRL","available":10}` | `POST /internal/reservations`<br>body `{"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","lines":[{"productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","quantity":1},{"productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","quantity":2}]}` | `201`<br>body `{"reservationId":"4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","state":"reserved","lines":[{"productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","quantity":1},{"productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","quantity":2}],"expiresAt":"2026-10-02T11:00:00Z"}` | `reservationId` uuid; `expiresAt` ISO-8601 UTC timestamp; everything else exact |
| `a request to reserve stock for an order that already has a reservation` | `a reservation exists {"reservationId":"4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","state":"reserved","lines":[{"productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","quantity":1},{"productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","quantity":2}],"expiresAt":"2026-10-02T11:00:00Z"}` | `POST /internal/reservations`<br>body `{"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","lines":[{"productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","quantity":1},{"productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","quantity":2}]}` | `200`<br>body `{"reservationId":"4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","state":"reserved","lines":[{"productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","quantity":1},{"productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","quantity":2}],"expiresAt":"2026-10-02T11:00:00Z"}` | none (every value comes from the provider state) |
| `a request to reserve more stock than is available` | `a product exists {"productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","sku":"ESP-MACH-01","name":"Espresso Machine","priceMinor":14900,"currency":"BRL","available":5}`<br>`a product exists {"productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","sku":"CB-1KG","name":"Coffee Beans 1kg","priceMinor":2450,"currency":"BRL","available":1}` | `POST /internal/reservations`<br>body `{"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","lines":[{"productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","quantity":1},{"productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","quantity":2}]}` | `409`<br>`Content-Type: application/problem+json`<br>body `{"type":"https://ecommerce.example/problems/insufficient-stock","title":"Insufficient stock","status":409,"detail":"One or more products cannot be reserved in the requested quantity.","instance":"/internal/reservations","correlationId":"3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13","unavailableLines":[{"productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","requested":2,"available":1}]}` | `type`, `title`, `status` exact; `detail` and `correlationId` string; `unavailableLines` exact (every short line, in request order) |
| `a request to reserve stock for withdrawn and unknown products` | `a product is withdrawn {"productId":"a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d","sku":"KTL-VINT-01","name":"Vintage Kettle","priceMinor":25900,"currency":"BRL","available":3}`<br>`no product exists {"productId":"d4c3b2a1-9f8e-4d7c-8b6a-5f4e3d2c1b0a"}` | `POST /internal/reservations`<br>body `{"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a13","lines":[{"productId":"a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d","quantity":1},{"productId":"d4c3b2a1-9f8e-4d7c-8b6a-5f4e3d2c1b0a","quantity":1}]}` | `409`<br>`Content-Type: application/problem+json`<br>body `{"type":"https://ecommerce.example/problems/insufficient-stock","title":"Insufficient stock","status":409,"detail":"One or more products cannot be reserved in the requested quantity.","instance":"/internal/reservations","correlationId":"3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13","unavailableLines":[{"productId":"a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d","requested":1,"available":0},{"productId":"d4c3b2a1-9f8e-4d7c-8b6a-5f4e3d2c1b0a","requested":1,"available":0}]}` | `type`, `title`, `status` exact; `detail` and `correlationId` string; `unavailableLines` exact (withdrawn and unknown count as `available` 0) |
| `a request to commit a reservation` | `a reservation exists {"reservationId":"4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","state":"reserved","lines":[{"productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","quantity":1},{"productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","quantity":2}],"expiresAt":"2026-10-02T11:00:00Z"}` | `POST /internal/reservations/4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15/commit` | `204`<br>no body | none (no body) |
| `a request to commit a reservation that is already committed` | `a reservation exists {"reservationId":"4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","state":"committed","lines":[{"productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","quantity":1},{"productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","quantity":2}],"expiresAt":"2026-10-02T11:00:00Z"}` | `POST /internal/reservations/4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15/commit` | `204`<br>no body | none (no body) |
| `a request to release a reservation` | `a reservation exists {"reservationId":"4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","state":"reserved","lines":[{"productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","quantity":1},{"productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","quantity":2}],"expiresAt":"2026-10-02T11:00:00Z"}` | `POST /internal/reservations/4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15/release` | `204`<br>no body | none (no body) |
| `a request to release a reservation that is already released` | `a reservation exists {"reservationId":"4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","state":"released","lines":[{"productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","quantity":1},{"productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","quantity":2}],"expiresAt":"2026-10-02T11:00:00Z"}` | `POST /internal/reservations/4a8f2c60-9d13-4b7e-a5c1-3e6d0b9f7a15/release` | `204`<br>no body | none (no body) |
| `a request for the pricing of several products to freeze order lines` | `a product exists {"productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","sku":"ESP-MACH-01","name":"Espresso Machine","priceMinor":14900,"currency":"BRL","available":5}`<br>`a product exists {"productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","sku":"CB-1KG","name":"Coffee Beans 1kg","priceMinor":2450,"currency":"BRL","available":10}`<br>`no product exists {"productId":"d4c3b2a1-9f8e-4d7c-8b6a-5f4e3d2c1b0a"}` | `POST /internal/products/pricing`<br>body `{"productIds":["9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","d4c3b2a1-9f8e-4d7c-8b6a-5f4e3d2c1b0a"]}` | `200`<br>body `{"items":[{"productId":"9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01","sku":"ESP-MACH-01","name":"Espresso Machine","price":{"amountMinor":14900,"currency":"BRL"},"available":5,"saleState":"active"},{"productId":"3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02","sku":"CB-1KG","name":"Coffee Beans 1kg","price":{"amountMinor":2450,"currency":"BRL"},"available":10,"saleState":"active"}]}` | none (every value comes from the provider states); the unknown id is omitted from `items` |

### 2.4 order to payment (consumer `order`, provider `payment`)

Contract: `payment-internal.yaml`. Verification owner: payment.

| Description | Provider state(s) | Request | Expected response | Matched by type |
|---|---|---|---|---|
| `a request to charge an order with an approved payment method` | `no payment exists for the order {"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10"}` | `POST /internal/charges`<br>`Idempotency-Key: 6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f`<br>body `{"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","amount":{"amountMinor":19800,"currency":"BRL"},"paymentMethodRef":"tok_sim_approve_4242"}` | `201`<br>body `{"attemptId":"c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","kind":"charge","outcome":"approved","providerReference":"sim_ch_000123","createdAt":"2026-10-02T10:15:01Z"}` | `attemptId` uuid; `providerReference` string; `createdAt` ISO-8601 UTC timestamp; `orderId`, `kind`, `outcome` exact |
| `a request to charge an order that the provider declines` | `no payment exists for the order {"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12"}` | `POST /internal/charges`<br>`Idempotency-Key: 2b3c4d5e-6f70-4a81-92b3-c4d5e6f70a81`<br>body `{"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","amount":{"amountMinor":4913,"currency":"BRL"},"paymentMethodRef":"tok_sim_approve_4242"}` | `201`<br>body `{"attemptId":"8e0d1c2b-3a4f-4b5c-9d6e-7f8a9b0c1d2e","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12","kind":"charge","outcome":"declined","declineCategory":"insufficient_funds","providerReference":"sim_ch_000124","createdAt":"2026-10-02T10:30:00Z"}` | `attemptId` uuid; `providerReference` string; `createdAt` ISO-8601 UTC timestamp; `outcome`, `declineCategory` exact (amount ending in 13 is declined `insufficient_funds`) |
| `a request to charge an order while the provider is unreachable` | `no payment exists for the order {"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a11"}` | `POST /internal/charges`<br>`Idempotency-Key: 3c4d5e6f-7081-4b92-a3c4-d5e6f7081b92`<br>body `{"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a11","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","amount":{"amountMinor":4900,"currency":"BRL"},"paymentMethodRef":"tok_sim_unreachable"}` | `201`<br>body `{"attemptId":"d3e4f5a6-b7c8-4d9e-8f0a-1b2c3d4e5f60","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a11","kind":"charge","outcome":"pending","createdAt":"2026-10-02T10:20:00Z"}` | `attemptId` uuid; `createdAt` ISO-8601 UTC timestamp; `outcome` exact; `providerReference` and `declineCategory` absent (the consumer must tolerate their absence) |
| `a request to charge an order again with the same idempotency key` | `a charge attempt exists {"attemptId":"c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","idempotencyKey":"6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f","amountMinor":19800,"currency":"BRL","paymentMethodRef":"tok_sim_approve_4242","outcome":"approved","providerReference":"sim_ch_000123","createdAt":"2026-10-02T10:15:01Z"}` | `POST /internal/charges`<br>`Idempotency-Key: 6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f`<br>body `{"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","amount":{"amountMinor":19800,"currency":"BRL"},"paymentMethodRef":"tok_sim_approve_4242"}` | `200`<br>body `{"attemptId":"c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","kind":"charge","outcome":"approved","providerReference":"sim_ch_000123","createdAt":"2026-10-02T10:15:01Z"}` | none (the same attempt, every value comes from the provider state) |
| `a request to refund an approved charge` | `an approved charge exists {"attemptId":"c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","amountMinor":19800,"currency":"BRL"}` | `POST /internal/refunds`<br>`Idempotency-Key: 9c8b7a69-5847-4362-9d1e-0f1a2b3c4d5e`<br>body `{"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","attemptId":"c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50","amount":{"amountMinor":19800,"currency":"BRL"}}` | `201`<br>body `{"refundId":"4d5e6f70-8192-4a3b-8c4d-5e6f70819203","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","attemptId":"c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50","amount":{"amountMinor":19800,"currency":"BRL"},"status":"recorded","createdAt":"2026-10-02T11:00:00Z"}` | `refundId` uuid; `createdAt` ISO-8601 UTC timestamp; everything else exact |
| `a request to refund an approved charge again with the same idempotency key` | `a refund exists {"refundId":"4d5e6f70-8192-4a3b-8c4d-5e6f70819203","attemptId":"c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","idempotencyKey":"9c8b7a69-5847-4362-9d1e-0f1a2b3c4d5e","amountMinor":19800,"currency":"BRL","createdAt":"2026-10-02T11:00:00Z"}` | `POST /internal/refunds`<br>`Idempotency-Key: 9c8b7a69-5847-4362-9d1e-0f1a2b3c4d5e`<br>body `{"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","attemptId":"c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50","amount":{"amountMinor":19800,"currency":"BRL"}}` | `200`<br>body `{"refundId":"4d5e6f70-8192-4a3b-8c4d-5e6f70819203","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","attemptId":"c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50","amount":{"amountMinor":19800,"currency":"BRL"},"status":"recorded","createdAt":"2026-10-02T11:00:00Z"}` | none (the same refund, every value comes from the provider state) |

### 2.5 order to identity (delivery address) (consumer `order`, provider `identity`)

Contract: `identity-internal.yaml` `getAccountAddress`. Verification owner: identity.

| Description | Provider state(s) | Request | Expected response | Matched by type |
|---|---|---|---|---|
| `a request for an address owned by the account` | `an address exists {"recipientName":"Ada Lovelace","line1":"12 Analytical Street","line2":"Flat 2","city":"London","region":"England","postalCode":"N1 9GU","countryCode":"GB","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","addressId":"5f0c1a52-3a43-4a53-9f58-7a1d8b9d2c11"}` | `GET /internal/accounts/7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d/addresses/5f0c1a52-3a43-4a53-9f58-7a1d8b9d2c11` | `200`<br>body `{"recipientName":"Ada Lovelace","line1":"12 Analytical Street","line2":"Flat 2","city":"London","region":"England","postalCode":"N1 9GU","countryCode":"GB"}` | none (every value comes from the provider state) |
| `a request for an address owned by another account` | `an address exists {"recipientName":"Ada Lovelace","line1":"12 Analytical Street","line2":"Flat 2","city":"London","region":"England","postalCode":"N1 9GU","countryCode":"GB","accountId":"9e8d7c6b-5a49-4382-9f1e-0d2c4b6a8e10","addressId":"6a1d2b63-4b54-4b64-8a69-8b2e9c0e3d22"}` | `GET /internal/accounts/7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d/addresses/6a1d2b63-4b54-4b64-8a69-8b2e9c0e3d22` | `404`<br>`Content-Type: application/problem+json`<br>body `{"type":"https://ecommerce.example/problems/not-found","title":"Not found","status":404,"detail":"Address not found.","correlationId":"3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"}` | `type`, `title`, `status` exact; `detail` and `correlationId` string |
| `a request for an address that does not exist` | `no address exists {"accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","addressId":"7b2e3c74-5c65-4c75-9b7a-9c3f0d1f4e33"}` | `GET /internal/accounts/7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d/addresses/7b2e3c74-5c65-4c75-9b7a-9c3f0d1f4e33` | `404`<br>`Content-Type: application/problem+json`<br>body `{"type":"https://ecommerce.example/problems/not-found","title":"Not found","status":404,"detail":"Address not found.","correlationId":"3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"}` | `type`, `title`, `status` exact; `detail` and `correlationId` string |

### 2.6 notification to identity (contact details) (consumer `notification`, provider `identity`)

Contract: `identity-internal.yaml` `getAccountContact`. Verification owner: identity.

| Description | Provider state(s) | Request | Expected response | Matched by type |
|---|---|---|---|---|
| `a request for the contact details of an account with email only` | `an account exists {"accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","email":"ada@example.test","phoneVerified":false,"channels":["email"]}` | `GET /internal/accounts/7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d/contact` | `200`<br>body `{"accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","email":"ada@example.test","phoneVerified":false,"channels":["email"],"anonymised":false}` | none (every value comes from the provider state); `phoneNumber` absent |
| `a request for the contact details of an account that opted in to SMS` | `an account exists {"accountId":"3b5d7f91-2c4e-4a68-9b0d-1f3a5c7e9b24","email":"grace@example.test","phoneNumber":"+5511987654321","phoneVerified":true,"channels":["email","sms"]}` | `GET /internal/accounts/3b5d7f91-2c4e-4a68-9b0d-1f3a5c7e9b24/contact` | `200`<br>body `{"accountId":"3b5d7f91-2c4e-4a68-9b0d-1f3a5c7e9b24","email":"grace@example.test","phoneNumber":"+5511987654321","phoneVerified":true,"channels":["email","sms"],"anonymised":false}` | none (every value comes from the provider state) |
| `a request for the contact details of an anonymised account` | `an account is anonymised {"accountId":"c8a6e4d2-0b9f-4c71-8a35-6e2d4f8b1c07","pseudonym":"anon-4f9c2d71"}` | `GET /internal/accounts/c8a6e4d2-0b9f-4c71-8a35-6e2d4f8b1c07/contact` | `200`<br>body `{"accountId":"c8a6e4d2-0b9f-4c71-8a35-6e2d4f8b1c07","email":"anon-4f9c2d71@anonymised.invalid","phoneVerified":false,"channels":[],"anonymised":true}` | `email` regex `^[^@\s]+@anonymised\.invalid$`; `channels` is an empty array, `anonymised` is true, `phoneNumber` absent |
| `a request for the contact details of an unknown account` | `no account exists {"accountId":"f0e1d2c3-b4a5-4968-8776-5a4b3c2d1e0f"}` | `GET /internal/accounts/f0e1d2c3-b4a5-4968-8776-5a4b3c2d1e0f/contact` | `404`<br>`Content-Type: application/problem+json`<br>body `{"type":"https://ecommerce.example/problems/not-found","title":"Not found","status":404,"detail":"Account not found.","correlationId":"3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"}` | `type`, `title`, `status` exact; `detail` and `correlationId` string |

### 2.7 cart to catalog (pricing) (consumer `cart`, provider `catalog`)

Contract: `catalog-internal.yaml` `getProductPricing`, `getProductsPricing`. Verification owner: catalog.

| Description | Provider state(s) | Request | Expected response | Matched by type |
|---|---|---|---|---|
| `a request for the pricing of a product that is in stock` | `a product exists {"productId":"0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21","sku":"TRS-001","name":"Trail Running Shoes","priceMinor":9490,"currency":"BRL","available":5}` | `GET /internal/products/0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21/pricing` | `200`<br>body `{"productId":"0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21","sku":"TRS-001","name":"Trail Running Shoes","price":{"amountMinor":9490,"currency":"BRL"},"available":5,"saleState":"active"}` | none (every value comes from the provider state) |
| `a request for the pricing of a product that is out of stock` | `a product exists {"productId":"b7e1c4d2-3f58-4a96-8d20-5c1e9a7b3d44","sku":"MUG-CER-01","name":"Ceramic Mug","priceMinor":3290,"currency":"BRL","available":0}` | `GET /internal/products/b7e1c4d2-3f58-4a96-8d20-5c1e9a7b3d44/pricing` | `200`<br>body `{"productId":"b7e1c4d2-3f58-4a96-8d20-5c1e9a7b3d44","sku":"MUG-CER-01","name":"Ceramic Mug","price":{"amountMinor":3290,"currency":"BRL"},"available":0,"saleState":"active"}` | none (every value comes from the provider state) |
| `a request for the pricing of a product that is withdrawn` | `a product is withdrawn {"productId":"a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d","sku":"KTL-VINT-01","name":"Vintage Kettle","priceMinor":25900,"currency":"BRL","available":3}` | `GET /internal/products/a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d/pricing` | `200`<br>body `{"productId":"a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d","sku":"KTL-VINT-01","name":"Vintage Kettle","price":{"amountMinor":25900,"currency":"BRL"},"available":3,"saleState":"withdrawn"}` | none (every value comes from the provider state) |
| `a request for the pricing of an unknown product` | `no product exists {"productId":"d4c3b2a1-9f8e-4d7c-8b6a-5f4e3d2c1b0a"}` | `GET /internal/products/d4c3b2a1-9f8e-4d7c-8b6a-5f4e3d2c1b0a/pricing` | `404`<br>`Content-Type: application/problem+json`<br>body `{"type":"https://ecommerce.example/problems/not-found","title":"Not found","status":404,"detail":"Product not found.","correlationId":"3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"}` | `type`, `title`, `status` exact; `detail` and `correlationId` string |
| `a request for the pricing of several products` | `a product exists {"productId":"0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21","sku":"TRS-001","name":"Trail Running Shoes","priceMinor":9490,"currency":"BRL","available":5}`<br>`a product exists {"productId":"b7e1c4d2-3f58-4a96-8d20-5c1e9a7b3d44","sku":"MUG-CER-01","name":"Ceramic Mug","priceMinor":3290,"currency":"BRL","available":0}`<br>`no product exists {"productId":"d4c3b2a1-9f8e-4d7c-8b6a-5f4e3d2c1b0a"}` | `POST /internal/products/pricing`<br>body `{"productIds":["0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21","b7e1c4d2-3f58-4a96-8d20-5c1e9a7b3d44","d4c3b2a1-9f8e-4d7c-8b6a-5f4e3d2c1b0a"]}` | `200`<br>body `{"items":[{"productId":"0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21","sku":"TRS-001","name":"Trail Running Shoes","price":{"amountMinor":9490,"currency":"BRL"},"available":5,"saleState":"active"},{"productId":"b7e1c4d2-3f58-4a96-8d20-5c1e9a7b3d44","sku":"MUG-CER-01","name":"Ceramic Mug","price":{"amountMinor":3290,"currency":"BRL"},"available":0,"saleState":"active"}]}` | none (every value comes from the provider states); the unknown id is omitted from `items` |

## 3. Message (event) interactions

Message pacts (Pact V4 asynchronous messages, content type `application/json`) describe what a consumer reads from
an event. The provider verification produces the event from the provider state and compares it with the pact. Every
message carries the metadata `topic` and `kafkaKey` shown below; `kafkaKey` equals the envelope `aggregateId`.

**Global matching rules for every event**: `type`, `version` (1), `producer` and `aggregateId` are exact;
`eventId` is matched as `uuid`; `occurredAt` and every other timestamp as `timestamp`; `correlationId` by regex
`^[A-Za-z0-9-]{1,64}$`. Every value not named as matched by type is exact and comes from the provider-state parameters
or from the fixture order or account the state describes (its content is the content of the example envelope below).
`paymentId` in the payment events is the `attemptId` of the charge (section 2.4).

**Duplicate delivery (FR-022)**: every consumer pact is accompanied by a consumer-side test that delivers the same
envelope (same `eventId`) twice through `IdempotentConsumer` and asserts the effect listed in the "Duplicate-delivery
expectation" column happens once. The provider side verifies that `eventId` is unique per event and stable across
outbox relay retries, and that `correlationId` is copied from the originating request.

### 3.1 Event envelope examples

Each example is the full envelope the provider must be able to produce (payload schemas in `events.yaml`). The rows
of section 3.2 refer to them by name.


#### `AccountRegistered`: topic `identity.account.v1`, Kafka key = `accountId` = `7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d`

Metadata: `{"topic":"identity.account.v1","kafkaKey":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"}`. Matched by type beyond the global rules: `verificationToken` string (opaque secret); `tokenExpiresAt` timestamp.

```json
{
  "eventId": "ee000001-0000-4000-8000-000000000001",
  "type": "AccountRegistered",
  "version": 1,
  "occurredAt": "2026-10-02T10:00:00Z",
  "aggregateId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "identity",
  "payload": {
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "recipient": {
      "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
      "email": "ada@example.test",
      "phone": null,
      "preferredChannels": [
        "email"
      ]
    },
    "verificationToken": "pact-example-verification-token",
    "tokenExpiresAt": "2026-10-03T10:00:00Z"
  }
}
```

#### `AccountVerified`: topic `identity.account.v1`, Kafka key = `accountId` = `7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d`

Metadata: `{"topic":"identity.account.v1","kafkaKey":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"}`. Matched by type beyond the global rules: `verifiedAt` timestamp.

```json
{
  "eventId": "ee000002-0000-4000-8000-000000000002",
  "type": "AccountVerified",
  "version": 1,
  "occurredAt": "2026-10-02T10:05:00Z",
  "aggregateId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "identity",
  "payload": {
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "recipient": {
      "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
      "email": "ada@example.test",
      "phone": null,
      "preferredChannels": [
        "email"
      ]
    },
    "verifiedAt": "2026-10-02T10:05:00Z"
  }
}
```

#### `PasswordResetRequested`: topic `identity.account.v1`, Kafka key = `accountId` = `7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d`

Metadata: `{"topic":"identity.account.v1","kafkaKey":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"}`. Matched by type beyond the global rules: `resetToken` string (opaque secret); `tokenExpiresAt` timestamp.

```json
{
  "eventId": "ee000003-0000-4000-8000-000000000003",
  "type": "PasswordResetRequested",
  "version": 1,
  "occurredAt": "2026-10-02T10:10:00Z",
  "aggregateId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "identity",
  "payload": {
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "recipient": {
      "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
      "email": "ada@example.test",
      "phone": null,
      "preferredChannels": [
        "email"
      ]
    },
    "resetToken": "pact-example-reset-token",
    "tokenExpiresAt": "2026-10-02T11:10:00Z"
  }
}
```

#### `AccountDeleted`: topic `identity.account.v1`, Kafka key = `accountId` = `7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d`

Metadata: `{"topic":"identity.account.v1","kafkaKey":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"}`. Matched by type beyond the global rules: `deletedAt` timestamp.

```json
{
  "eventId": "ee000004-0000-4000-8000-000000000004",
  "type": "AccountDeleted",
  "version": 1,
  "occurredAt": "2026-10-02T12:00:00Z",
  "aggregateId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "identity",
  "payload": {
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "pseudonym": "anon-4f9c2d71",
    "deletedAt": "2026-10-02T12:00:00Z"
  }
}
```

#### `OrderPlaced`: topic `order.order.v1`, Kafka key = `orderId` = `0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10`

Metadata: `{"topic":"order.order.v1","kafkaKey":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10"}`. Matched by type beyond the global rules: `orderNumber` string.

```json
{
  "eventId": "ee000005-0000-4000-8000-000000000005",
  "type": "OrderPlaced",
  "version": 1,
  "occurredAt": "2026-10-02T10:15:00Z",
  "aggregateId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "order",
  "payload": {
    "orderId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10",
    "orderNumber": "ORD-20261002-0001",
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "lines": [
      {
        "productId": "9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01",
        "name": "Espresso Machine",
        "quantity": 1,
        "unitPrice": {
          "amountMinor": 14900,
          "currency": "BRL"
        }
      },
      {
        "productId": "3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02",
        "name": "Coffee Beans 1kg",
        "quantity": 2,
        "unitPrice": {
          "amountMinor": 2450,
          "currency": "BRL"
        }
      }
    ],
    "total": {
      "amountMinor": 19800,
      "currency": "BRL"
    },
    "orderStatus": "placed",
    "paymentStatus": "pending",
    "deliveryAddress": {
      "recipientName": "Ada Lovelace",
      "line1": "12 Analytical Street",
      "line2": "Flat 2",
      "city": "London",
      "postalCode": "N1 9GU",
      "country": "GB"
    },
    "paymentMethodRef": "tok_sim_approve_4242",
    "idempotencyKey": "6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
    "recipient": {
      "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
      "email": "ada@example.test",
      "phone": null,
      "preferredChannels": [
        "email"
      ]
    }
  }
}
```

#### `OrderPaid`: topic `order.order.v1`, Kafka key = `orderId` = `0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10`

Metadata: `{"topic":"order.order.v1","kafkaKey":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10"}`. Matched by type beyond the global rules: `orderNumber` string; `paidAt` timestamp.

```json
{
  "eventId": "ee000006-0000-4000-8000-000000000006",
  "type": "OrderPaid",
  "version": 1,
  "occurredAt": "2026-10-02T10:15:01Z",
  "aggregateId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "order",
  "payload": {
    "orderId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10",
    "orderNumber": "ORD-20261002-0001",
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "lines": [
      {
        "productId": "9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01",
        "name": "Espresso Machine",
        "quantity": 1,
        "unitPrice": {
          "amountMinor": 14900,
          "currency": "BRL"
        }
      },
      {
        "productId": "3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02",
        "name": "Coffee Beans 1kg",
        "quantity": 2,
        "unitPrice": {
          "amountMinor": 2450,
          "currency": "BRL"
        }
      }
    ],
    "total": {
      "amountMinor": 19800,
      "currency": "BRL"
    },
    "orderStatus": "placed",
    "paymentStatus": "approved",
    "deliveryAddress": {
      "recipientName": "Ada Lovelace",
      "line1": "12 Analytical Street",
      "line2": "Flat 2",
      "city": "London",
      "postalCode": "N1 9GU",
      "country": "GB"
    },
    "paymentId": "c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50",
    "paidAt": "2026-10-02T10:15:01Z",
    "recipient": {
      "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
      "email": "ada@example.test",
      "phone": null,
      "preferredChannels": [
        "email"
      ]
    }
  }
}
```

#### `OrderPaymentFailed`: topic `order.order.v1`, Kafka key = `orderId` = `0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12`

Metadata: `{"topic":"order.order.v1","kafkaKey":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12"}`. Matched by type beyond the global rules: `orderNumber` string.

```json
{
  "eventId": "ee000007-0000-4000-8000-000000000007",
  "type": "OrderPaymentFailed",
  "version": 1,
  "occurredAt": "2026-10-02T10:30:00Z",
  "aggregateId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "order",
  "payload": {
    "orderId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12",
    "orderNumber": "ORD-20261002-0003",
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "total": {
      "amountMinor": 4913,
      "currency": "BRL"
    },
    "orderStatus": "cancelled",
    "paymentStatus": "failed",
    "cancellationReason": "PAYMENT_FAILED",
    "reasonCategory": "insufficient_funds",
    "recipient": {
      "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
      "email": "ada@example.test",
      "phone": null,
      "preferredChannels": [
        "email"
      ]
    }
  }
}
```

#### `OrderShipped`: topic `order.order.v1`, Kafka key = `orderId` = `0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10`

Metadata: `{"topic":"order.order.v1","kafkaKey":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10"}`. Matched by type beyond the global rules: `orderNumber` string; `changedAt` timestamp; `changedBy` uuid.

```json
{
  "eventId": "ee000008-0000-4000-8000-000000000008",
  "type": "OrderShipped",
  "version": 1,
  "occurredAt": "2026-10-03T09:00:00Z",
  "aggregateId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "order",
  "payload": {
    "orderId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10",
    "orderNumber": "ORD-20261002-0001",
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "orderStatus": "shipped",
    "paymentStatus": "approved",
    "changedAt": "2026-10-03T09:00:00Z",
    "changedBy": "e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22",
    "recipient": {
      "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
      "email": "ada@example.test",
      "phone": null,
      "preferredChannels": [
        "email"
      ]
    }
  }
}
```

#### `OrderDelivered`: topic `order.order.v1`, Kafka key = `orderId` = `0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10`

Metadata: `{"topic":"order.order.v1","kafkaKey":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10"}`. Matched by type beyond the global rules: `orderNumber` string; `changedAt` timestamp; `changedBy` uuid.

```json
{
  "eventId": "ee000009-0000-4000-8000-000000000009",
  "type": "OrderDelivered",
  "version": 1,
  "occurredAt": "2026-10-05T14:30:00Z",
  "aggregateId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "order",
  "payload": {
    "orderId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10",
    "orderNumber": "ORD-20261002-0001",
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "orderStatus": "delivered",
    "paymentStatus": "approved",
    "changedAt": "2026-10-05T14:30:00Z",
    "changedBy": "e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22",
    "recipient": {
      "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
      "email": "ada@example.test",
      "phone": null,
      "preferredChannels": [
        "email"
      ]
    }
  }
}
```

#### `OrderCancelled#shopper`: topic `order.order.v1`, Kafka key = `orderId` = `0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10`

Metadata: `{"topic":"order.order.v1","kafkaKey":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10"}`. Matched by type beyond the global rules: `orderNumber` string; `cancelledAt` timestamp.

```json
{
  "eventId": "ee000010-0000-4000-8000-000000000010",
  "type": "OrderCancelled",
  "version": 1,
  "occurredAt": "2026-10-02T10:45:00Z",
  "aggregateId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "order",
  "payload": {
    "orderId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10",
    "orderNumber": "ORD-20261002-0001",
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "lines": [
      {
        "productId": "9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01",
        "quantity": 1
      },
      {
        "productId": "3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02",
        "quantity": 2
      }
    ],
    "total": {
      "amountMinor": 19800,
      "currency": "BRL"
    },
    "orderStatus": "cancelled",
    "paymentStatus": "approved",
    "reason": "SHOPPER_REQUEST",
    "refundRequired": true,
    "paymentId": "c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50",
    "cancelledAt": "2026-10-02T10:45:00Z",
    "recipient": {
      "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
      "email": "ada@example.test",
      "phone": null,
      "preferredChannels": [
        "email"
      ]
    }
  }
}
```

#### `OrderCancelled#expired`: topic `order.order.v1`, Kafka key = `orderId` = `0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a11`

Metadata: `{"topic":"order.order.v1","kafkaKey":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a11"}`. Matched by type beyond the global rules: `orderNumber` string; `cancelledAt` timestamp.

```json
{
  "eventId": "ee000011-0000-4000-8000-000000000011",
  "type": "OrderCancelled",
  "version": 1,
  "occurredAt": "2026-10-02T10:50:00Z",
  "aggregateId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a11",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "order",
  "payload": {
    "orderId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a11",
    "orderNumber": "ORD-20261002-0002",
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "lines": [
      {
        "productId": "3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02",
        "quantity": 2
      }
    ],
    "total": {
      "amountMinor": 4900,
      "currency": "BRL"
    },
    "orderStatus": "cancelled",
    "paymentStatus": "failed",
    "reason": "PAYMENT_EXPIRED",
    "refundRequired": false,
    "paymentId": null,
    "cancelledAt": "2026-10-02T10:50:00Z",
    "recipient": {
      "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
      "email": "ada@example.test",
      "phone": null,
      "preferredChannels": [
        "email"
      ]
    }
  }
}
```

#### `PaymentApproved`: topic `payment.payment.v1`, Kafka key = `paymentId` = `c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50`

Metadata: `{"topic":"payment.payment.v1","kafkaKey":"c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50"}`. Matched by type beyond the global rules: `providerReference` string.

```json
{
  "eventId": "ee000012-0000-4000-8000-000000000012",
  "type": "PaymentApproved",
  "version": 1,
  "occurredAt": "2026-10-02T10:15:01Z",
  "aggregateId": "c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "payment",
  "payload": {
    "paymentId": "c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50",
    "orderId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10",
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "amount": {
      "amountMinor": 19800,
      "currency": "BRL"
    },
    "status": "approved",
    "idempotencyKey": "6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
    "providerReference": "sim_ch_000123"
  }
}
```

#### `PaymentDeclined`: topic `payment.payment.v1`, Kafka key = `paymentId` = `8e0d1c2b-3a4f-4b5c-9d6e-7f8a9b0c1d2e`

Metadata: `{"topic":"payment.payment.v1","kafkaKey":"8e0d1c2b-3a4f-4b5c-9d6e-7f8a9b0c1d2e"}`. Matched by type beyond the global rules: `providerReference` string.

```json
{
  "eventId": "ee000013-0000-4000-8000-000000000013",
  "type": "PaymentDeclined",
  "version": 1,
  "occurredAt": "2026-10-02T10:30:00Z",
  "aggregateId": "8e0d1c2b-3a4f-4b5c-9d6e-7f8a9b0c1d2e",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "payment",
  "payload": {
    "paymentId": "8e0d1c2b-3a4f-4b5c-9d6e-7f8a9b0c1d2e",
    "orderId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12",
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "amount": {
      "amountMinor": 4913,
      "currency": "BRL"
    },
    "status": "declined",
    "idempotencyKey": "2b3c4d5e-6f70-4a81-92b3-c4d5e6f70a81",
    "providerReference": "sim_ch_000124",
    "reasonCategory": "insufficient_funds"
  }
}
```

#### `PaymentPending`: topic `payment.payment.v1`, Kafka key = `paymentId` = `d3e4f5a6-b7c8-4d9e-8f0a-1b2c3d4e5f60`

Metadata: `{"topic":"payment.payment.v1","kafkaKey":"d3e4f5a6-b7c8-4d9e-8f0a-1b2c3d4e5f60"}`. Matched by type beyond the global rules: none beyond the global rules; `providerReference` and `reasonCategory` absent.

```json
{
  "eventId": "ee000014-0000-4000-8000-000000000014",
  "type": "PaymentPending",
  "version": 1,
  "occurredAt": "2026-10-02T10:20:00Z",
  "aggregateId": "d3e4f5a6-b7c8-4d9e-8f0a-1b2c3d4e5f60",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "payment",
  "payload": {
    "paymentId": "d3e4f5a6-b7c8-4d9e-8f0a-1b2c3d4e5f60",
    "orderId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a11",
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "amount": {
      "amountMinor": 4900,
      "currency": "BRL"
    },
    "status": "pending",
    "idempotencyKey": "3c4d5e6f-7081-4b92-a3c4-d5e6f7081b92",
    "pendingReason": "PROVIDER_UNAVAILABLE"
  }
}
```

#### `RefundRecorded`: topic `payment.payment.v1`, Kafka key = `paymentId` = `c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50`

Metadata: `{"topic":"payment.payment.v1","kafkaKey":"c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50"}`. Matched by type beyond the global rules: `providerReference` string.

```json
{
  "eventId": "ee000015-0000-4000-8000-000000000015",
  "type": "RefundRecorded",
  "version": 1,
  "occurredAt": "2026-10-02T11:00:00Z",
  "aggregateId": "c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50",
  "correlationId": "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
  "producer": "payment",
  "payload": {
    "refundId": "4d5e6f70-8192-4a3b-8c4d-5e6f70819203",
    "paymentId": "c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50",
    "orderId": "0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10",
    "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
    "amount": {
      "amountMinor": 19800,
      "currency": "BRL"
    },
    "providerReference": "sim_rf_000045",
    "recipient": {
      "accountId": "7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d",
      "email": "ada@example.test",
      "phone": null,
      "preferredChannels": [
        "email"
      ]
    }
  }
}
```

### 3.2 Message pacts per consumer


#### consumer `notification`, provider `identity`

| Description | Provider state | Event (topic, key) and full example envelope | Duplicate-delivery expectation |
|---|---|---|---|
| `an AccountRegistered event for a new account` | `an account was registered {"accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","email":"ada@example.test"}` | `AccountRegistered` on `identity.account.v1`, key `accountId` (see `AccountRegistered` in 3.1; envelope `type` `AccountRegistered`, `version` 1, `producer` `identity`) | The same `eventId` delivered twice creates one Notification (unique on sourceEventId, kind, channel) and sends one verification email. |
| `an AccountVerified event for a verified account` | `an account was verified {"accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","email":"ada@example.test"}` | `AccountVerified` on `identity.account.v1`, key `accountId` (see `AccountVerified` in 3.1; envelope `type` `AccountVerified`, `version` 1, `producer` `identity`) | The same `eventId` delivered twice updates the recipient read model once and creates no second Notification. |
| `a PasswordResetRequested event for an account` | `a password reset was requested {"accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","email":"ada@example.test"}` | `PasswordResetRequested` on `identity.account.v1`, key `accountId` (see `PasswordResetRequested` in 3.1; envelope `type` `PasswordResetRequested`, `version` 1, `producer` `identity`) | The same `eventId` delivered twice creates one Notification and sends one reset email. |
| `an AccountDeleted event for a deleted account` | `an account was deleted {"accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","pseudonym":"anon-4f9c2d71"}` | `AccountDeleted` on `identity.account.v1`, key `accountId` (see `AccountDeleted` in 3.1; envelope `type` `AccountDeleted`, `version` 1, `producer` `identity`) | The same `eventId` delivered twice anonymises the recipient once and suppresses queued notifications once. |

#### consumer `notification`, provider `order`

| Description | Provider state | Event (topic, key) and full example envelope | Duplicate-delivery expectation |
|---|---|---|---|
| `an OrderPaid event for the order confirmation` | `an order was paid {"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"}` | `OrderPaid` on `order.order.v1`, key `orderId` (see `OrderPaid` in 3.1; envelope `type` `OrderPaid`, `version` 1, `producer` `order`) | The same `eventId` delivered twice creates one order-confirmation Notification and sends one email. |
| `an OrderPaymentFailed event for the payment failure message` | `an order payment failed {"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","reasonCategory":"insufficient_funds"}` | `OrderPaymentFailed` on `order.order.v1`, key `orderId` (see `OrderPaymentFailed` in 3.1; envelope `type` `OrderPaymentFailed`, `version` 1, `producer` `order`) | The same `eventId` delivered twice creates one payment-failure Notification. |
| `an OrderShipped event for the shipping message` | `an order was shipped {"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"}` | `OrderShipped` on `order.order.v1`, key `orderId` (see `OrderShipped` in 3.1; envelope `type` `OrderShipped`, `version` 1, `producer` `order`) | The same `eventId` delivered twice creates one Notification per permitted channel, once. |
| `an OrderDelivered event for the delivery message` | `an order was delivered {"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"}` | `OrderDelivered` on `order.order.v1`, key `orderId` (see `OrderDelivered` in 3.1; envelope `type` `OrderDelivered`, `version` 1, `producer` `order`) | The same `eventId` delivered twice creates one Notification per permitted channel, once. |
| `an OrderCancelled event for the cancellation message` | `an order was cancelled {"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","reason":"SHOPPER_REQUEST","paymentStatus":"approved"}` | `OrderCancelled` on `order.order.v1`, key `orderId` (see `OrderCancelled#shopper` in 3.1; envelope `type` `OrderCancelled`, `version` 1, `producer` `order`) | The same `eventId` delivered twice creates one cancellation Notification. |

#### consumer `notification`, provider `payment`

| Description | Provider state | Event (topic, key) and full example envelope | Duplicate-delivery expectation |
|---|---|---|---|
| `a RefundRecorded event for the refund confirmation` | `a refund was recorded {"refundId":"4d5e6f70-8192-4a3b-8c4d-5e6f70819203","paymentId":"c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"}` | `RefundRecorded` on `payment.payment.v1`, key `paymentId` (see `RefundRecorded` in 3.1; envelope `type` `RefundRecorded`, `version` 1, `producer` `payment`) | The same `eventId` delivered twice creates one refund-confirmation Notification. |

#### consumer `order`, provider `payment`

| Description | Provider state | Event (topic, key) and full example envelope | Duplicate-delivery expectation |
|---|---|---|---|
| `a PaymentApproved event for an order awaiting payment` | `a payment was approved {"paymentId":"c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"}` | `PaymentApproved` on `payment.payment.v1`, key `paymentId` (see `PaymentApproved` in 3.1; envelope `type` `PaymentApproved`, `version` 1, `producer` `payment`) | The same `eventId` delivered twice moves paymentStatus pending to approved once and publishes one `OrderPaid`. |
| `a PaymentDeclined event for an order awaiting payment` | `a payment was declined {"paymentId":"8e0d1c2b-3a4f-4b5c-9d6e-7f8a9b0c1d2e","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","reasonCategory":"insufficient_funds"}` | `PaymentDeclined` on `payment.payment.v1`, key `paymentId` (see `PaymentDeclined` in 3.1; envelope `type` `PaymentDeclined`, `version` 1, `producer` `payment`) | The same `eventId` delivered twice sets paymentStatus failed and cancels the order once (`PAYMENT_FAILED`) and publishes one `OrderPaymentFailed`. |
| `a PaymentPending event for an order awaiting payment` | `a payment is pending {"paymentId":"d3e4f5a6-b7c8-4d9e-8f0a-1b2c3d4e5f60","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a11","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"}` | `PaymentPending` on `payment.payment.v1`, key `paymentId` (see `PaymentPending` in 3.1; envelope `type` `PaymentPending`, `version` 1, `producer` `payment`) | The same `eventId` delivered twice annotates the order once; paymentStatus stays pending and nothing else changes. |
| `a RefundRecorded event for a cancelled order` | `a refund was recorded {"refundId":"4d5e6f70-8192-4a3b-8c4d-5e6f70819203","paymentId":"c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50","orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"}` | `RefundRecorded` on `payment.payment.v1`, key `paymentId` (see `RefundRecorded` in 3.1; envelope `type` `RefundRecorded`, `version` 1, `producer` `payment`) | The same `eventId` delivered twice sets `refundRecordedAt` once. |

#### consumer `order`, provider `identity`

| Description | Provider state | Event (topic, key) and full example envelope | Duplicate-delivery expectation |
|---|---|---|---|
| `an AccountDeleted event for the owner of open orders` | `an account was deleted {"accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","pseudonym":"anon-4f9c2d71"}` | `AccountDeleted` on `identity.account.v1`, key `accountId` (see `AccountDeleted` in 3.1; envelope `type` `AccountDeleted`, `version` 1, `producer` `identity`) | The same `eventId` delivered twice replaces the account reference with the pseudonym once and schedules the address scrub once. |

#### consumer `catalog`, provider `order`

| Description | Provider state | Event (topic, key) and full example envelope | Duplicate-delivery expectation |
|---|---|---|---|
| `an OrderPaid event that commits the reservation` | `an order was paid {"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"}` | `OrderPaid` on `order.order.v1`, key `orderId` (see `OrderPaid` in 3.1; envelope `type` `OrderPaid`, `version` 1, `producer` `order`) | The same `eventId` delivered twice commits the reservation once (onHand and reserved reduced once) and publishes one `StockCommitted`. |
| `an OrderPaymentFailed event that releases the reservation` | `an order payment failed {"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","reasonCategory":"insufficient_funds"}` | `OrderPaymentFailed` on `order.order.v1`, key `orderId` (see `OrderPaymentFailed` in 3.1; envelope `type` `OrderPaymentFailed`, `version` 1, `producer` `order`) | The same `eventId` delivered twice releases the reservation once and publishes one `StockReservationReleased`. |
| `an OrderCancelled event after the payment expired` | `an order was cancelled {"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a11","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","reason":"PAYMENT_EXPIRED","paymentStatus":"failed"}` | `OrderCancelled` on `order.order.v1`, key `orderId` (see `OrderCancelled#expired` in 3.1; envelope `type` `OrderCancelled`, `version` 1, `producer` `order`) | The same `eventId` delivered twice releases the reservation once; an already released reservation (synchronous release confirmed earlier) is left untouched. |

#### consumer `payment`, provider `order`

| Description | Provider state | Event (topic, key) and full example envelope | Duplicate-delivery expectation |
|---|---|---|---|
| `an OrderPlaced event that triggers the charge` | `an order was placed {"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d"}` | `OrderPlaced` on `order.order.v1`, key `orderId` (see `OrderPlaced` in 3.1; envelope `type` `OrderPlaced`, `version` 1, `producer` `order`) | The same `eventId` delivered twice creates one charge attempt (the checkout `idempotencyKey` is the charge key, so it also converges with the synchronous `POST /internal/charges`) and never a second approved charge. |
| `an OrderCancelled event that triggers the refund` | `an order was cancelled {"orderId":"0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10","accountId":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","reason":"SHOPPER_REQUEST","paymentStatus":"approved"}` | `OrderCancelled` on `order.order.v1`, key `orderId` (see `OrderCancelled#shopper` in 3.1; envelope `type` `OrderCancelled`, `version` 1, `producer` `order`) | The same `eventId` delivered twice records one refund and publishes one `RefundRecorded`. |

Events with consumers outside the matrix (`AccountDeleted` and `OrderPaid` for cart, stock events for order) are
covered by the integration tests of those consumers, not by a Pact, because `pact-matrix.md` does not list them.

## 4. Shared token contract

Access tokens are JWTs issued by identity (15 minutes) and validated by the gateway and by every service against the
JWKS document (section 2.1); services never call identity per request (`docs/service-conventions.md` section 3).

| Item | Contract |
|---|---|
| Header | `alg` `EdDSA` (Ed25519), `kid` = a `kid` of the JWKS, `typ` `JWT` |
| `sub` | account id (UUID) |
| `roles` | non-empty array of `shopper` or `operator` |
| `iss` | `JWT_ISSUER` (default `https://identity.ecommerce.local`) |
| `aud` | `JWT_AUDIENCE` (default `ecommerce-api`), issued as a string; validators accept a string or an array containing it |
| `exp`, `iat` | seconds since epoch, `exp - iat` = 900 |
| `jti` | unique token id (UUID) |

Example claims: `{"sub":"7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d","roles":["shopper"],"iss":"https://identity.ecommerce.local","aud":"ecommerce-api","iat":1790935200,"exp":1790936100,"jti":"1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"}`.

Verification owner: identity. It is checked by (a) the identity contract test that issues a token and asserts the
claims above and that its `kid` is present in the JWKS (the rotation interaction in 2.1 keeps old tokens valid), and
(b) the gateway, order, payment and notification tests that validate a token signed with the key of the first
JWKS example. It is not a separate Pact file: the only HTTP dependency of the consumers is the JWKS interaction.

## 5. Health pact

Existing since feature 002 (template `HealthConsumerPactTest`), unchanged and present in every service.

| Consumer | Provider | Description | Provider state | Request | Expected response |
|---|---|---|---|---|---|
| `platform-probe` | each service (`identity`, `catalog`, `cart`, `order`, `payment`, `notification`) | `a health check` | `the <ctx> service is running` (for example `the identity service is running`) | `GET /actuator/health`, `Accept: application/json` (management port 8081 in Compose; the test uses the mock server) | `200`, body `{"status":"UP"}` |

Note: the catalog module that exists today uses the state text `the catalogue service is running`
(`services/catalog/.../HealthConsumerPactTest.kt`); the consumer and the provider verification of that module already
agree on it, so it stays until that test is renamed to `the catalog service is running` in one change with its provider.

