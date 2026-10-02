# Quickstart: Verifying the Harness Gates

**Feature**: 001-harness-quality-gates

Run everything from the repository root. Prerequisites: `bash`, `jq`, `git`; the Gradle wrapper
and a Gradle module once feature 002 has landed (until then the gates are silent no-ops, which is
itself the FR-010 behaviour). Commands below use the single-command entry point
`.claude/hooks/run-gate.sh`; the hooks can also be fed hook JSON directly.

## 0. Run the self-tests (no build needed)

```bash
.claude/hooks/tests/run-all.sh          # silent on success; prints failing test names otherwise
.github/scripts/tests/run-all.sh        # PR-gate scripts against fixture Pitest XML and diffs
```

Expected: exit 0 and no output. Covers SC-003, SC-004 and the dry-run/bypass behaviour on the
fixture monorepo (`.claude/hooks/tests/fixtures/monorepo/`).

## 1. Dry-run each gate (SC-006)

```bash
HOOK_DRY_RUN=1 .claude/hooks/run-gate.sh file services/demo/domain/src/main/kotlin/demo/Price.kt
HOOK_DRY_RUN=1 .claude/hooks/run-gate.sh complete
HOOK_DRY_RUN=1 .claude/hooks/run-gate.sh pr
```

Expected: each prints `DRY [...]: <command>` lines (ktlint/detekt, targeted `test --tests`,
`check`, incremental `pitest -Pharness.mutation.classes=...`, frontend lint/test, full `pitest`,
mutation and survivor scripts) and exits 0 without executing anything. A newcomer can read the list
in under a minute (SC-006).

## 2. Trigger a failing per-file check (SC-001, SC-003)

1. Introduce a style violation (for example an unused import) or break an assertion in a test that
   covers the file.
2. Edit through Claude Code, or pipe synthetic hook JSON:

```bash
echo '{"tool_input":{"file_path":"'"$PWD"'/services/demo/domain/src/main/kotlin/demo/Price.kt"}}' \
  | .claude/hooks/post-edit-check.sh; echo "exit=$?"
```

Expected: `exit=2`; stderr shows the failing check name, the exact command, and at most 60 log lines
(SC-003). Feedback arrives within 60 s for a typical module (SC-001; the duration is appended to
`.claude/.cache/gate-timing.log`). After fixing the file the same command prints nothing and
returns 0. A file with no covering test runs style checks only and never fails with "no tests
found"; a file under `build/`, `node_modules/` or outside the repo does nothing.

## 3. Force the end-of-task gate and the loop guard (SC-002, SC-004)

```bash
rm -f .claude/.cache/last-full-check                       # absent marker = full run
echo '{"stop_hook_active":false}' | .claude/hooks/stop-full-check.sh; echo "exit=$?"
echo '{"stop_hook_active":true}'  | .claude/hooks/stop-full-check.sh; echo "exit=$?"
```

Expected with a persistently failing check: first call `exit=2` with the report; second call
`exit=0` with the same report as a JSON `systemMessage` (never blocked twice in a row, SC-004).
With everything green: no output and the marker is refreshed; running the first command again
finishes in under 2 s with no output because nothing changed (SC-002). Without a build configured
the gate is a silent no-op.

## 4. Bypass once (FR-019)

```bash
HOOK_BYPASS=1 HOOK_BYPASS_REASON="flaky upstream outage" .claude/hooks/run-gate.sh complete
tail -n 1 .claude/.cache/bypass.log
```

Expected: exit 0, no checks run, and one new log line with timestamp, gate, user and reason. Run the
same command again without `HOOK_BYPASS`: the gate runs normally (the bypass was scoped to one run).
Paste the log lines into the "Gate bypasses" section of the pull request; the PR gate fails if the
section is missing. `HOOK_BYPASS` has no effect when `CI=true`.

## 5. Verify the pull-request gate (SC-005)

1. Push a branch and open a pull request. The `pr-gate` check appears as the single required status
   (summary of at most 20 lines on the run page).
2. Negative checks, each expected to fail `pr-gate` naming the module, threshold and score or the
   file:line of the mutant:
   - lower a test so a `domain` module's mutation score drops below 80 % (or below its entry in
     `quality/mutation-baseline.json`);
   - leave a surviving mutant on a changed line and omit it from the `## Mutant justifications`
     section; adding `path:line reason` to the PR description and re-running makes it pass.
3. Locally rehearse the same sequence: `.claude/hooks/run-gate.sh pr` (or `HOOK_DRY_RUN=1 ...` to
   list the steps).

## Outcome-to-criterion map

| Step | Success criterion | Observable outcome |
|------|-------------------|--------------------|
| 0, 2 | SC-001 | Pass/fail feedback within 60 s in 90 % of edits (timing log) |
| 3 | SC-002 | Complete gate within 15 min typical (20 min abort); under 2 s when nothing changed |
| 0, 2 | SC-003 | Failures show check name, command, at most 60 lines; passes print zero bytes |
| 3 | SC-004 | Second consecutive failing attempt reports without blocking |
| 5 | SC-005 | One consolidated `pr-gate` status; no merge with failure or sub-threshold module |
| 1 | SC-006 | Dry run lists every command; readable in under a minute following `docs/harness.md` |
| 0 | SC-007 | Quiet defaults verified by tests; context share measured with `rtk`/`tokensave` reports over a working day |
