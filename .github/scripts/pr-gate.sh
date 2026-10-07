#!/usr/bin/env bash
# Pull-request gate (FR-014), the same steps locally (`.claude/hooks/run-gate.sh pr`) and in CI
# (.github/workflows/pr-gate.yml), in this order, stopping at the first failure:
#   1. verify             ./gradlew -q verify: the repository gate of feature 002, which includes the full Pitest
#                         run (no class filter) of every domain/application module. Skipped with --skip-verify:
#                         in CI it runs in the reusable verify.yml job and its Pitest reports are handed over.
#   2. mutation-gate      .github/scripts/mutation-gate.sh: per-module threshold and non-decreasing baseline
#   3. surviving-mutants  .github/scripts/surviving-mutants.sh: survivors on changed lines need a justification
#   4. <package>:stryker  npx --no-install stryker run, only for a frontend package with a stryker.config.*
# Silent on success. A failed step prints `pr-gate step FAILED: <step>`, the command and at most 60 lines of
# its output. With $GITHUB_STEP_SUMMARY set, a table of at most 20 lines is appended to it.
# Usage: pr-gate.sh [--dry-run] [--skip-verify] [--base-ref REF] [--diff FILE] [--body-file FILE]
#   (HOOK_DRY_RUN=1 behaves like --dry-run; --base-ref/--diff/--body-file go to the mutation scripts)
set -uo pipefail
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/pitest-reports.sh
. "$SCRIPTS/lib/pitest-reports.sh"

TAIL_LINES=60
SUMMARY_MAX_ROWS=16
usage() {
  echo "Usage: pr-gate.sh [--dry-run] [--skip-verify] [--base-ref REF] [--diff FILE] [--body-file FILE]" >&2
  exit 64
}
DRY="${HOOK_DRY_RUN:-}"; SKIP_VERIFY=""; BASE_ARGS=(); SURV_ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --skip-verify) SKIP_VERIFY=1 ;;
    --base-ref) [ $# -ge 2 ] || usage; BASE_ARGS+=("$1" "$2"); shift ;;
    --diff|--body-file) [ $# -ge 2 ] || usage; SURV_ARGS+=("$1" "$2"); shift ;;
    *) usage ;;
  esac
  shift
done
ROOT="$(pr_root)"
export PR_GATE_ROOT="$ROOT"
cd "$ROOT" || exit 1

LOG="$(mktemp)"; trap 'rm -f "$LOG"' EXIT
ROWS=""; FAILED=""
row() { ROWS+="| $1 | $2 |"$'\n'; }

# step NAME CMD...: run in the repository root, output to the log; on failure print the bounded report.
step() {
  local name="$1"; shift
  if [ -n "$FAILED" ]; then row "$name" "not run"; return; fi
  if [ -n "$DRY" ]; then
    case "$1" in
      "$SCRIPTS"/*) "$@" --dry-run ;;
      *) echo "DRY [$name]: $*" ;;
    esac
    return
  fi
  if "$@" >"$LOG" 2>&1 </dev/null; then row "$name" pass; return; fi
  FAILED="$name"; row "$name" FAILED
  echo "pr-gate step FAILED: $name"
  local shown="$*"; shown="${shown#"$SCRIPTS"/}"
  [ "$shown" = "$*" ] || shown=".github/scripts/$shown"
  echo "\$ $shown"
  local total; total="$(wc -l <"$LOG" | tr -d ' ')"
  if [ "$total" -le "$TAIL_LINES" ]; then cat "$LOG"
  else echo "($((total - TAIL_LINES + 1)) earlier lines omitted)"; tail -n "$((TAIL_LINES - 1))" "$LOG"; fi
}

if [ -n "$SKIP_VERIFY" ]; then row verify "in verify.yml"
else step verify ./gradlew -q verify; fi
step mutation-gate "$SCRIPTS/mutation-gate.sh" ${BASE_ARGS[@]+"${BASE_ARGS[@]}"}
step surviving-mutants "$SCRIPTS/surviving-mutants.sh" ${BASE_ARGS[@]+"${BASE_ARGS[@]}"} ${SURV_ARGS[@]+"${SURV_ARGS[@]}"}

# Stryker (frontend mutation testing) only where configured: frontend/ since feature 005 (docs/harness.md).
while IFS= read -r pkgjson; do
  [ -n "$pkgjson" ] || continue
  pkg="$(dirname "$pkgjson")"
  ls "$pkg"/stryker.config.* "$pkg"/stryker.conf.* >/dev/null 2>&1 || continue
  step "${pkg##*/}:stryker" env CI=1 npx --no-install --prefix "$pkg" stryker run
done <<<"$(find "$ROOT" -maxdepth 3 -name package.json -not -path '*/node_modules/*' -not -path '*/.claude/*' 2>/dev/null)"

if [ -z "$DRY" ] && [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### pr-gate steps"
    echo
    echo "| step | result |"
    echo "| --- | --- |"
    printf '%s' "$ROWS" | head -n "$SUMMARY_MAX_ROWS"
  } >>"$GITHUB_STEP_SUMMARY"
fi
[ -z "$FAILED" ]
