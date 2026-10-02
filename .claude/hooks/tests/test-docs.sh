#!/usr/bin/env bash
# FR-020 / SC-006: docs/harness.md documents every gate and only references things that exist.
. "$(dirname "$0")/lib.sh"
DOC="$REPO_ROOT/docs/harness.md"
[ -f "$DOC" ] || { _t_fail "missing $DOC"; test_done; }
has() { grep -qiF -- "$1" "$DOC" || _t_fail "docs/harness.md must mention: $1"; }

# every gate with its trigger, checks, budget and the run / dry-run / bypass commands
for g in "Per-file gate" "End-of-task gate" "Pull-request gate"; do has "$g"; done
has "PostToolUse"; has "Stop hook"; has "pull_request"
has "300 s"; has "1200 s"; has "30 minutes"
has ".claude/hooks/run-gate.sh file"; has ".claude/hooks/run-gate.sh complete"; has ".claude/hooks/run-gate.sh pr"
has "HOOK_DRY_RUN=1"; has "HOOK_BYPASS=1"; has "HOOK_BYPASS_REASON"; has "HOOK_BUDGET_SECONDS"
has ".claude/.cache/bypass.log"; has ".claude/.cache/gate-timing.log"; has ".claude/.cache/last-full-check"
has "stop_hook_active"; has "systemMessage"
# report format and caps, mutation decisions, deferred pieces, operations
has "60 lines"; has "40 lines"; has "earlier lines omitted"
has "Arcmutate"; has "exclusions"; has "harness.pitest.arcmutate"
has "Stryker"; has "deferred"
has "Task '...' not found"
has "pr-gate"; has "branch protection"; has "ephemeral"; has "rtk gain"; has "tokensave"
has "SC-001"; has "SC-002"; has "SC-007"

has "./gradlew -q verify"; has "quality/mutation-baseline.json"; has "Mutant justifications"; has "Gate bypasses"

# every repository path the document mentions must exist (the pull-request gate has landed: nothing pending)
PENDING=" "
paths="$(grep -oE '`[A-Za-z0-9_.][A-Za-z0-9_./-]*`' "$DOC" | tr -d '`' | grep -E '^(\.claude|\.github|\.npmrc|build-logic|quality|gradle|docs|specs|scripts)(/|$)|^\.npmrc$' | sort -u)"
[ -n "$paths" ] || _t_fail "no repository paths found in the document"
for p in $paths; do
  case "$p" in */) p="${p%/}" ;; esac
  case "$p" in .claude/.cache/*) continue ;; esac   # runtime files, created on first use
  case "$p" in *'*'*) continue ;; esac
  [ -e "$REPO_ROOT/$p" ] && continue
  case "$PENDING" in *" $p "*) continue ;; esac
  _t_fail "docs/harness.md references a missing path: $p"
done
test_done
