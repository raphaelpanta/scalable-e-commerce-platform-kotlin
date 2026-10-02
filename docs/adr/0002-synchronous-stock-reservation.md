# ADR 0002: Synchronous stock reservation at checkout

- **Status**: Accepted 2026-10-02

## Context

Overselling is the main correctness risk of checkout: two shoppers may hold the last unit and pay at the same time
(SC-004, user story 4 scenario 3). Reserving only after payment succeeds would charge shoppers for stock that no
longer exists; reserving through an event after the order exists would make the refusal asynchronous and leave a
created order to clean up. The clarification session of 2026-10-02 fixed the timing as a requirement (FR-012).

## Decision

The order service reserves stock through a synchronous request to catalog, before any payment attempt.

| Step | Behaviour |
|---|---|
| Reserve | `POST /internal/reservations`, keyed by `orderId`, all-or-nothing across the lines. A shortage (including an unknown or withdrawn product) refuses the checkout with 409 `insufficient-stock` naming the unavailable lines; no order is created |
| Commit | On payment approval: `onHand -= qty`, `reserved -= qty` |
| Release | On payment failure, cancellation or expiry of a pending payment: `reserved -= qty`; if the reservation was already committed, a cancellation restocks (`onHand += qty`) |
| Non-negative stock | The reservation is a conditional update (`available >= requested`) with optimistic locking, so exactly one of two concurrent reservations of the last unit succeeds |
| Idempotency | One reservation per order; reserving again returns the existing one, commit and release are legal once from the right state and are no-ops afterwards |
| Safety net | Catalog also consumes `OrderPaid` (commit), `OrderPaymentFailed` and `OrderCancelled` (release), so a lost synchronous commit or release still converges |

Catalog does not consume `OrderPlaced`: the reservation already exists when the order is placed.

## Consequences

- Insufficient stock is reported immediately and nothing needs cleaning up; the cart revision check runs first, so a
  stale checkout never reserves anything ([ADR 0003](0003-cart-revision-checkout.md)).
- Checkout depends on catalog being available: a catalog outage is a 503 for checkout (one synchronous hop,
  allowed by Principle VI) and is covered by a Pact (order -> catalog).
- Reserved stock is invisible to other shoppers until released, so abandoned pending payments must expire (30 minutes,
  [ADR 0001](0001-two-status-order-model.md)).
- Every transition is idempotent and reachable by two paths (HTTP and event); both paths are tested for convergence.
- Stock changes are published as audit events (`StockReserved`, `StockCommitted`, `StockReservationReleased`); no
  rejection event exists, the refusal is the call's error response.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| Reserve after payment approval | Shoppers could be charged for unavailable stock and need a refund |
| Reserve asynchronously on `OrderPlaced` | The order would exist before its refusal, so a shortage needs a compensating cancellation and the shopper learns late |
| Distributed transaction or saga with orchestrator service | Disproportionate for one hop between two services; the reservation state machine is the compensation |
| Decrement stock at add-to-cart | Holds stock for browsing shoppers and abandoned carts |

## References

- [spec.md](../../specs/004-ecommerce-platform-mvp/spec.md): Clarifications 2026-10-02, FR-012, SC-004
- [data-model.md](../../specs/004-ecommerce-platform-mvp/data-model.md): section 3.2 (reservation state machine)
- [catalog-internal.yaml](../../contracts/internal/catalog-internal.yaml), [events.yaml](../../contracts/asyncapi/events.yaml)
- [research.md](../../specs/004-ecommerce-platform-mvp/research.md): section 8
- [architecture.md](../architecture.md): checkout journey
