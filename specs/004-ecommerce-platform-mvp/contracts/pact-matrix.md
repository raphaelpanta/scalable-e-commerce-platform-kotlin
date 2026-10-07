# Consumer-Driven Contract (Pact) Matrix

Feature: 004-ecommerce-platform-mvp. Covers FR-021, FR-022, FR-028 and user story 9.
Synchronous HTTP interactions use Pact HTTP contracts; asynchronous events use Pact message
contracts (the event payload shapes are defined in `contracts/asyncapi/`).

## How the matrix works

- The **consumer** writes the contract test describing only what it actually uses, and publishes the
  resulting pact with its version.
- The **provider** verifies every published pact of its consumers in its own build against its real
  adapter layer (not mocks of itself). For message pacts the provider verifies it can produce a
  message matching the consumer's expectation.
- Verification owner is therefore the provider side in every row; the consumer owns authoring and
  keeping the pact minimal. Provider states are implemented by the provider, named in the pact.
- **Breaking a published contract fails the build** (constitution Principle V): a provider change
  that makes any published consumer pact unverifiable fails the provider's pipeline, naming the
  contract and the consumer affected (user story 9, scenario 2). A consumer pact change is
  publishable only when the pipeline's "can I deploy" check against provider versions passes.
- Only the affected service's pipeline runs for a change (FR-028), plus the verification of pacts
  that involve it.

## HTTP interactions

| Consumer | Provider | Interactions covered | Verification owner |
| --- | --- | --- | --- |
| gateway | identity | `GET` JWKS document (valid key set, rotation with a new `kid`, unavailable provider state) | identity |
| order | cart | read the shopper's cart (non-empty, empty, unknown cart), clear the cart after a successful order | cart |
| order | catalog | reserve stock for cart lines (success, insufficient stock listing unavailable lines), commit reservation, release reservation (idempotent when already released), read current price and name for freezing lines | catalog |
| order | payment | authorise a charge (approved, declined with category, provider unreachable resulting in pending, same idempotency key returns the same attempt); internal network endpoint, not published at the gateway. Refunds are not called by order: payment records them from `OrderCancelled` | payment |
| order | identity | read a delivery address owned by the shopper (found, not owned, not found) | identity |
| order | identity | read the recipient contact for order events (email only) at checkout, so order events carry the `recipient` snapshot (added 2026-10-02 during implementation; same shape as the notification lookup) | identity |
| notification | identity | read recipient contact details and notification preferences for an account (email only, SMS opted in with verified number, anonymised account) | identity |
| cart | catalog | read price and availability for a product (in stock, out of stock, withdrawn, unknown product) and for several products in one call | catalog |
| platform-probe (image `HEALTHCHECK`, Compose health check, CI start-and-health) | identity, catalog, cart, order, payment, notification (each its own pact, `platform-probe-<service>.json`) | `GET /actuator/health/readiness` on the management port answers 200 with `"status":"UP"` (provider state `the <identity, catalogue, cart, order, payment or notification> service is running`; other body members are allowed); readiness path per T129 | the provider of each pact |
| gateway | all services | the gateway has no health pact; its own health endpoints are checked by integration tests (`ManagementPortIT`), not by a pact | not applicable |
| storefront | identity | sign in, register, verify email, refresh, sign out, own profile, addresses, notification and phone verification preferences, password reset, account deletion (feature 005, rows I1 to I18 of the linked matrix) | identity |
| storefront | catalog | products and categories (list, filter, sort, page, detail), operator stock reads and adjustments (feature 005, rows C1 to C7 of the linked matrix) | catalog |
| storefront | cart | anonymous and account cart reads and mutations, merge, revision (feature 005, rows K1 to K7 of the linked matrix) | cart |
| storefront | order | checkout with `cartRevision` and `Idempotency-Key` (created, `price-changed`, stock refusal), order list and detail, cancel, operator advance and cancel (feature 005, rows O1 to O11 of the linked matrix) | order |
| storefront | payment | the payment reads the storefront uses (operator simulator rules, payment status of an order; feature 005, rows P1 and P2 of the linked matrix) | payment |
| storefront | gateway | the browser-session routes (cookie sign-in, refresh and sign-out summaries, idle expiry, origin check, cart cookie) and the telemetry routes (feature 005, rows G1 to G19 of the linked matrix); the gateway is a provider for the first time (`contractVerify` in `services/gateway`) | gateway |

The six `storefront` rows are additions of feature 005: interactions, provider states and verification topology are in
[`specs/005-storefront-dev-bootstrap/contracts/pact-matrix.md`](../../005-storefront-dev-bootstrap/contracts/pact-matrix.md)
(pacts `storefront-<provider>.json` in the same root `build/pacts`, published by the storefront pipeline
`.github/workflows/storefront.yml`).

## Message (event) interactions

| Consumer | Provider (producer) | Events covered | Verification owner |
| --- | --- | --- | --- |
| notification | identity | account registered (verification message), password reset requested, account deleted (stop notifications) | identity |
| notification | order | order paid (confirmation with number, lines, total, address), order shipped, order delivered, order cancelled | order |
| notification | payment | payment failed (decline category, order id) | payment |
| order | payment | payment approved and payment failed outcomes for an order whose payment is still pending and is retried later | payment |
| order | identity | account deleted (anonymise the owner of open orders under a pseudonym, FR-007) | identity |
| catalog | order | order cancelled or expired (stock returned when the synchronous release was not confirmed) | order |

All consumers MUST tolerate duplicates (FR-022): each message pact includes a redelivery
expectation (the same event id twice yields one effect), verified in the consumer's test, while the
provider verifies stable `eventId` and `correlationId` fields.

## Shared token contract

| Consumer | Provider | Interaction | Verification owner |
| --- | --- | --- | --- |
| order, payment, notification | identity | claims in the access token: subject account id, roles (`shopper`, `operator`), issuer, audience, expiry. Services read the validated claims passed by the gateway and never call identity per request | identity |

## Rules for contract changes

1. Adding an optional response field or a new event field is non-breaking; consumers must ignore
   unknown fields.
2. Removing or renaming a field, tightening a value set, or changing status codes used by a
   consumer breaks the pact; the provider must first support both shapes, consumers migrate, then
   the old shape is removed in a later release.
3. Public OpenAPI files in `contracts/openapi/` describe the gateway-facing surface; Pact files
   describe what internal consumers actually depend on. A provider change must satisfy both.
4. Each pact states the provider state it needs by name, and the provider owns the state setup in
   its test fixtures so that verification is deterministic and independent of other services.
