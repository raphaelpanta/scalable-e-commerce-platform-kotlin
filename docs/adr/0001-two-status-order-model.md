# ADR 0001: Two-status order model

- **Status**: Accepted 2026-10-02

## Context

User story 5 listed payment states (such as "payment failed") inside the order lifecycle while FR-015 kept payment
separate, so the spec contradicted itself. A single status that mixes fulfilment and payment multiplies states
(`placed-unpaid`, `placed-paid`, `cancelled-refunded`, ...), hides which dimension changed, and makes events
ambiguous for consumers such as catalog, payment and notification. The decision was taken in the clarification
session of 2026-10-02 ([spec](../../specs/004-ecommerce-platform-mvp/spec.md), Clarifications).

## Decision

An order carries two independent statuses.

| Status | Values and transitions |
|---|---|
| `orderStatus` | `placed` -> `preparing` -> `shipped` -> `delivered`; `cancelled` reachable only from `placed` or `preparing` |
| `paymentStatus` | `pending` -> `approved` or `failed` |

Rules:

- An order moves to `preparing` only while `paymentStatus` is `approved`; with `pending` or `failed` the transition
  is refused.
- A `failed` payment, or a payment still `pending` after 30 minutes, cancels the order with the recorded reason
  `PAYMENT_FAILED` or `PAYMENT_EXPIRED` and releases stock. On expiry `paymentStatus` is set to `failed` so no
  cancelled order keeps a `pending` payment.
- Every cancellation records a reason: `SHOPPER_REQUEST` (only while `placed`), `OPERATOR` (while `placed` or
  `preparing`), `PAYMENT_FAILED`, `PAYMENT_EXPIRED`. Transitions outside these tables are refused and change nothing.
- Events never carry a combined status. `OrderPaid` means payment approved (the order stays `placed`);
  `OrderPaymentFailed` means payment failed and the order is cancelled with `PAYMENT_FAILED`; `OrderCancelled` covers
  `SHOPPER_REQUEST`, `OPERATOR` and `PAYMENT_EXPIRED`. Payment attempt outcomes stay inside the payment context.

The order context owns both statuses and updates `paymentStatus` only from payment outcomes.

## Consequences

- Each status has a small state machine that is easy to property-test; the guard "preparing requires approved" is the
  only coupling between them.
- The API (`orderStatus`, `paymentStatus`, `cancellationReason` in
  [order.yaml](../../contracts/openapi/order.yaml)) and the events ([events.yaml](../../contracts/asyncapi/events.yaml))
  expose both fields; clients render them side by side.
- A decline publishes `OrderPaymentFailed` only, so catalog sees exactly one release signal per cancellation.
- Cancelling an order whose payment is `approved` leaves `paymentStatus` `approved` and records a refund instead of
  adding a refunded state.
- A scheduled job in order is needed for the 30-minute expiry.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| One combined status (`pending-payment`, `paid`, `payment-failed`, `preparing`, ...) | State explosion, payment retries and refunds muddy the lifecycle, events carry ambiguous meaning |
| Payment status as a separate aggregate in the payment context only | Order history and the `preparing` guard need the payment result inside the order's transaction |
| No expiry (pending payments wait forever) | Stock stays reserved indefinitely; FR-015 requires cancelling after 30 minutes |

## References

- [spec.md](../../specs/004-ecommerce-platform-mvp/spec.md): Clarifications 2026-10-02, FR-015..FR-017, user story 5
- [data-model.md](../../specs/004-ecommerce-platform-mvp/data-model.md): sections 3.4, 3.5 and 4.1
- [plan.md](../../specs/004-ecommerce-platform-mvp/plan.md): "Design Decisions Resolving Spec Gaps"
- [architecture.md](../architecture.md): section 5
