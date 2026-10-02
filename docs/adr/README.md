# Architecture decision records

Decisions that shape the platform and are costly to reverse. The overview is in [../architecture.md](../architecture.md);
stack choices taken during planning are in
[research.md](../../specs/004-ecommerce-platform-mvp/research.md) and are not repeated here.

| # | Title | Status |
|---|---|---|
| [0001](0001-two-status-order-model.md) | Two-status order model | Accepted 2026-10-02 |
| [0002](0002-synchronous-stock-reservation.md) | Synchronous stock reservation at checkout | Accepted 2026-10-02 |
| [0003](0003-cart-revision-checkout.md) | Cart revision checkout | Accepted 2026-10-02 |

## Template

Copy the structure below into `NNNN-short-title.md` (next number, lower-case words joined by hyphens), add a row to
the table above and link the record from the decisions table of [../architecture.md](../architecture.md).

```markdown
# ADR NNNN: Title

- **Status**: Proposed | Accepted YYYY-MM-DD | Superseded by ADR NNNN

## Context
## Decision
## Consequences
## Alternatives considered
## References
```

An accepted record is not rewritten: a change of mind is a new ADR that supersedes the old one, whose status is
updated to point to it. Link to specs and contracts with relative paths.
