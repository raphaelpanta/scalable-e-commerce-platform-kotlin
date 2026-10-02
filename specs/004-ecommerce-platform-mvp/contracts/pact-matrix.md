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
| order | payment | authorise a charge (approved, declined with category, provider unreachable resulting in pending, same idempotency key returns the same attempt), refund an approved charge (success, refund replay with the same key); internal network endpoints, not published at the gateway | payment |
| order | identity | read a delivery address owned by the shopper (found, not owned, not found) | identity |
| order | identity | read the recipient contact for order events (email only) at checkout, so order events carry the `recipient` snapshot (added 2026-10-02 during implementation; same shape as the notification lookup) | identity |
| notification | identity | read recipient contact details and notification preferences for an account (email only, SMS opted in with verified number, anonymised account) | identity |
| cart | catalog | read price and availability for a product (in stock, out of stock, withdrawn, unknown product) and for several products in one call | catalog |
| gateway | all services | health endpoints are internal only and checked by integration tests, not pacts | not applicable |

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
