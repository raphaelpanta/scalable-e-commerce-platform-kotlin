# ADR 0003: Cart revision checkout

- **Status**: Accepted 2026-10-02

## Context

Prices are read live from catalog, so a product's price can change between the moment a shopper views the cart and
the moment they confirm checkout. Charging the new price silently is unfair; charging the old price loses money; and
the order must always freeze the current catalogue price (FR-011). The clarification session of 2026-10-02 chose
explicit acknowledgement by the shopper (FR-010, FR-011).

## Decision

The cart exposes an opaque `revision` and checkout must prove which cart the shopper saw.

- `revision` is returned with every cart view and mutation. It changes whenever any line, quantity or any line's
  current price changes and stays the same otherwise. It is derived (a digest of the cart version plus each line's
  product id, quantity and current unit price) and recomputed on each view. Clients compare it for equality only and
  never parse it.
- `POST /api/v1/orders` carries `cartRevision`, the last revision the shopper saw. Order reads the cart over the
  internal endpoint and compares revisions before anything else happens.
- If a price moved, checkout is refused with 409 `price-changed`: `changedLines` lists each line with old and new
  price and `currentCartRevision` is the revision to resubmit. No order, no reservation and no payment attempt exist
  yet, so nothing needs undoing.
- Resubmitting with `cartRevision` set to `currentCartRevision` accepts the new prices; the order freezes the current
  catalogue prices at confirmation.
- A refused checkout (stale revision or insufficient stock) stores no idempotency record, so the corrected request can
  be sent again; `Idempotency-Key` handling otherwise follows FR-013.
- The revision check precedes the stock reservation ([ADR 0002](0002-synchronous-stock-reservation.md)).

## Consequences

- No hidden price changes: the shopper always confirms the prices they will pay.
- Cart owns the revision algorithm; order treats it as an opaque string, so the cart can change the derivation
  without a contract change.
- Every cart view recomputes the revision from live catalogue prices, which adds a catalog pricing call to cart reads
  (already needed to flag changed lines).
- Pact interactions: order -> cart (read cart and revision) and cart -> catalog (pricing).
- A second checkout with a stale revision is refused again, so concurrent price changes cannot be missed; clients must
  re-read the cart after a 409.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| Always charge the current price without telling the shopper | Hidden price changes, poor trust and compliance risk |
| Honour the price in the cart (`priceAtAdd`) | Sells below the current price and lets stale carts dictate prices |
| Client sends the expected total | Doesn't say which lines changed and ties the contract to arithmetic the client can get wrong |
| Prices stored in a cart snapshot with a version number | Needs catalog price-change events and a replicated read model in cart, against the live-read design |

## References

- [spec.md](../../specs/004-ecommerce-platform-mvp/spec.md): Clarifications 2026-10-02, FR-010, FR-011
- [data-model.md](../../specs/004-ecommerce-platform-mvp/data-model.md): sections 3.3 and 3.4
- [order.yaml](../../contracts/openapi/order.yaml), [cart.yaml](../../contracts/openapi/cart.yaml),
  [cart-internal.yaml](../../contracts/internal/cart-internal.yaml)
- [research.md](../../specs/004-ecommerce-platform-mvp/research.md): section 10
- [architecture.md](../architecture.md): checkout journey
