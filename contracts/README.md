# Contracts

Machine-readable contracts of the platform. Once implementation starts these files are the **source of truth**:
the copies under `specs/004-ecommerce-platform-mvp/contracts/` are the design-time origin and are not kept in
sync any more. Change a contract here, in the same change as the code, and let the tests below fail the build when
code and contract drift apart.

```
contracts/
  openapi/     public HTTP contracts, one per service (what the gateway exposes)
  asyncapi/    domain events on Kafka (events.yaml)
  internal/    service-to-service HTTP contracts and the Pact reference
```

## How the three kinds of contract relate

| Kind | Files | Describes | Used by |
|---|---|---|---|
| Public | `openapi/{identity,catalog,cart,order,payment,notification}.yaml` | The `/api/v1/<context>/...` surface routed by the gateway (routes and rate-limit tiers in `specs/004-ecommerce-platform-mvp/contracts/gateway-routes.md`). Bearer JWT, RFC 9457 errors. | Frontend, operators, acceptance suite, the gateway route table |
| Asynchronous | `asyncapi/events.yaml` | Topics `<context>.<aggregate>.v1`, the common envelope and every payload. Delivery is at-least-once; consumers dedupe by `eventId`. | Producers (outbox) and consumers (`IdempotentConsumer`) in `platform-messaging` |
| Internal | `internal/{catalog,cart,payment,identity}-internal.yaml` | `/internal/**` endpoints that the public files omit, plus the unrouted `GET /.well-known/jwks.json`. Every operation requires `X-Internal-Token` and propagates `X-Correlation-Id`; errors reuse the public `Problem` shape (plus `correlationId`). Never routed by the gateway. | Internal HTTP clients (order, cart, notification) and, for JWKS, the gateway and all services |

The three are one system, not three documents: a checkout touches all of them. The shopper calls the public
`POST /api/v1/orders`; the order service calls the internal catalog, cart, payment and identity endpoints; the
outcome then travels as events (`OrderPlaced`, `PaymentApproved`, `OrderPaid`, ...) described in `events.yaml`. Shared
vocabulary is identical everywhere (`Money` = `{amountMinor, currency}`, UUID ids, UTC timestamps, the decline
category enum, the cancellation reasons); when a shared value changes, change every file that uses it.

## Pact files describe what consumers actually use

The OpenAPI and AsyncAPI files say what a provider **may** do; Pact files say what a consumer **depends on**. Each
consumer test writes a pact containing only the fields and status codes its code reads, and each provider verifies
all pacts addressed to it against its real adapter layer. A provider change must satisfy both the contract file and
every published pact. The exact interactions (description strings, provider-state names with parameters, request and
response examples, message envelopes, duplicate-delivery expectations, the shared token contract and the health pact)
are fixed in [`internal/pact-interactions.md`](internal/pact-interactions.md); consumers and providers implement
exactly those names. Test mechanics: `docs/service-conventions.md` section 6.

## Evolution rule: additive within v1

Within `/api/v1` and the `.v1` topics only additive changes are allowed:

- a new **optional** request field, response field, header or query parameter;
- a new endpoint or a new event type;
- a new enum value, **provided** consumers already tolerate unknown values (the producer announces it first; the
  decline categories, event types and statuses documented as extensible are the typical cases);
- a looser constraint (for example a larger `maxLength`).

Everything else is breaking: removing or renaming a field, making an optional field required, tightening a value
set or a constraint, changing a status code a consumer relies on, changing the meaning of a value, changing a topic's
key. A breaking change never edits `v1` in place. It creates `/api/v2/...` (public) or a `.v2` topic (events), runs in
parallel with `v1` during a migration window until every consumer, named by its Pact, has moved, and only then is
`v1` removed. For internal endpoints the same applies to the `/internal/**` surface: the provider first supports both
shapes, consumers migrate (their pact changes), then the old shape goes in a later release.

Consumers must ignore unknown fields and unknown enum values they do not act on. Breaking a published pact fails
the provider's build and names the contract and the consumer affected.

## Validation

Every YAML file must parse; a quick check without extra tooling:

```
ruby -ryaml -e 'ARGV.each { |f| YAML.load_file(f); puts "ok #{f}" }' contracts/openapi/*.yaml contracts/asyncapi/*.yaml contracts/internal/*.yaml
```

Every `/internal/**` path (and the JWKS path) of the four internal files must appear in `internal/pact-interactions.md`
and the other way round; add the interaction in the same change as the endpoint.
