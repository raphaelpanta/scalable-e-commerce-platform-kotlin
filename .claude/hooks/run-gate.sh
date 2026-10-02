#!/usr/bin/env bash
# Single entry point to run any quality gate by hand (FR-018).
#   run-gate.sh file <path>   per-file gate for one file (what PostToolUse runs after Write|Edit)
#   run-gate.sh complete      end-of-task gate (what Stop runs; skipped when nothing changed since the
#                             last green run, delete .claude/.cache/last-full-check to force a full run)
#   run-gate.sh pr            pull-request gate locally (.github/scripts/pr-gate.sh, same steps as CI)
# Environment: HOOK_DRY_RUN=1 prints the commands without running them; HOOK_BYPASS=1 skips the gate once
# and records a line in .claude/.cache/bypass.log (optional HOOK_BYPASS_REASON); HOOK_BUDGET_SECONDS
# overrides the time budget. Exit codes are those of the gate (0 pass, 2 blocked), 64 usage error,
# 69 pr gate script missing.
set -uo pipefail

HOOK_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/common.sh
. "$HOOK_DIR/lib/common.sh"
ROOT="$(hook_root)"

usage() {
  cat >&2 <<'USAGE'
Usage: run-gate.sh file <path> | complete | pr
  file <path>   per-file gate (style checks + tests covering <path>)
  complete      end-of-task gate (full check, incremental Pitest, frontend)
  pr            pull-request gate (verify, full Pitest, threshold, surviving mutants)
Environment: HOOK_DRY_RUN=1 (print commands), HOOK_BYPASS=1 (skip once, logged), HOOK_BUDGET_SECONDS
USAGE
  exit 64
}

[ $# -ge 1 ] || usage
cmd="$1"; shift
export CLAUDE_PROJECT_DIR="$ROOT"

case "$cmd" in
  file)
    [ $# -eq 1 ] && [ -n "$1" ] || usage
    f="$1"
    case "$f" in /*) ;; *) f="$PWD/$f" ;; esac
    jq -cn --arg f "$f" '{tool_name:"Write",tool_input:{file_path:$f},tool_response:{filePath:$f}}' |
      "$HOOK_DIR/post-edit-check.sh"
    exit $?
    ;;
  complete)
    [ $# -eq 0 ] || usage
    printf '{"hook_event_name":"Stop","stop_hook_active":false}' | "$HOOK_DIR/stop-full-check.sh"
    exit $?
    ;;
  pr)
    [ $# -eq 0 ] || usage
    script="$ROOT/.github/scripts/pr-gate.sh"
    if [ -z "${HOOK_DRY_RUN:-}" ] && hook_bypass pr-local -; then exit 0; fi
    if [ ! -x "$script" ]; then
      echo "run-gate.sh: $script not found or not executable (pull-request gate not installed)" >&2
      exit 69
    fi
    if [ -n "${HOOK_DRY_RUN:-}" ]; then exec "$script" --dry-run; fi
    exec "$script"
    ;;
  *) usage ;;
esac
