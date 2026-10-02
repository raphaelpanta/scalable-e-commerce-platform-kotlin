## Summary

<!-- What changes and why? Link the spec (specs/NNN-...) this implements. -->

## Constitution gate

- [ ] Tests exist and pass at each layer that applies (unit, property, integration, contract, behaviour).
- [ ] The threat model for this change was considered (Principle III); no secrets, tokens or personal data added.
- [ ] The mutation-testing threshold is held for the changed modules.
- [ ] Every surviving mutant on changed lines is justified in the description.
- [ ] The bypass log is empty (no hook or gate was bypassed).
- [ ] The Constitution Check in the relevant `plan.md` passed; deviations are justified in Complexity Tracking.

## Gate bypasses

<!-- Required, checked by the pr-gate status: write `none`, or paste the lines that HOOK_BYPASS=1 appended to
.claude/.cache/bypass.log while you worked on this branch (docs/harness.md, "Bypass"). -->

## Mutant justifications

<!-- One line per surviving mutant on a changed line that the pr-gate mutation job reports, in the format
`path:line reason`, for example:
services/catalog/domain/src/main/kotlin/com/ecommerce/catalog/domain/Price.kt:42 equivalent mutant: the bound is unreachable
Leave this section empty when there is none. -->

## Notes for reviewers

<!-- Risks, follow-ups, screenshots. This pull request will be squash-merged: make the title the commit subject. -->
