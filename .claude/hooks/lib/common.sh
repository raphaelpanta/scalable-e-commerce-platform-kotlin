# shellcheck disable=SC2034  # this library defines variables read by the scripts that source it
# shellcheck shell=bash
# Shared helpers for the harness hooks (post-edit-check.sh, stop-full-check.sh, run-gate.sh).
# Source it; it defines functions only and prints nothing. Bash 3.2 compatible (macOS default).
#
# Environment knobs (all optional):
#   CLAUDE_PROJECT_DIR   repository root (default: two levels above .claude/hooks)
#   HOOK_DRY_RUN=1       print the commands instead of running them
#   HOOK_BYPASS=1        skip the gate once, logging to .claude/.cache/bypass.log (ignored in CI)
#   HOOK_BYPASS_REASON   free text recorded in the bypass log line
#   HOOK_BUDGET_SECONDS  total time budget of one gate run (300 per-file, 1200 complete)

_HARNESS_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Failure-report caps (FR-006): lines of log shown per failed check.
HOOK_TAIL_LINES_FILE=60      # per-file gate
HOOK_TAIL_LINES_COMPLETE=40  # complete gate

# hook_root: absolute repository root.
hook_root() {
  if [ -n "${CLAUDE_PROJECT_DIR:-}" ]; then printf '%s\n' "$CLAUDE_PROJECT_DIR"
  else (cd "$_HARNESS_LIB_DIR/../../.." && pwd); fi
}

cache_dir() { printf '%s/.claude/.cache\n' "${ROOT:-$(hook_root)}"; }

_now() { date +%s; }
_iso_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# --- time budgets -----------------------------------------------------------------------------------

# deadline_init DEFAULT_SECONDS: starts the gate clock; HOOK_BUDGET_SECONDS overrides the default.
deadline_init() {
  GATE_BUDGET="${HOOK_BUDGET_SECONDS:-$1}"
  case "$GATE_BUDGET" in ''|*[!0-9]*) GATE_BUDGET="$1" ;; esac
  GATE_START="$(_now)"
  GATE_DEADLINE=$((GATE_START + GATE_BUDGET))
}
deadline_remaining() { local r=$((GATE_DEADLINE - $(_now))); [ "$r" -gt 0 ] && echo "$r" || echo 0; }
deadline_elapsed() { echo $(($(_now) - GATE_START)); }

# _descendants PID: PIDs of every descendant of PID, depth first.
_descendants() {
  local c
  for c in $(pgrep -P "$1" 2>/dev/null); do echo "$c"; _descendants "$c"; done
}

# run_budgeted SECONDS CMD...: run CMD with stdin closed and at most SECONDS to live.
# Output goes to the caller's descriptors. Returns the command's exit code, or 124 on timeout, after
# terminating the command and all its descendants (TERM, then KILL 2 s later; `timeout -k 5` when a
# timeout binary exists). The Gradle daemon is not a descendant of the client and is left alone.
run_budgeted() {
  local secs="$1"; shift
  [ "$secs" -ge 1 ] 2>/dev/null || secs=1
  if command -v timeout >/dev/null 2>&1; then timeout -k 5 "$secs" "$@" </dev/null; return $?; fi
  if command -v gtimeout >/dev/null 2>&1; then gtimeout -k 5 "$secs" "$@" </dev/null; return $?; fi
  local flag pid wd rc
  flag="$(mktemp 2>/dev/null)" || flag=""
  "$@" </dev/null &
  pid=$!
  (
    sleep "$secs"
    [ -n "$flag" ] && echo 1 >"$flag"
    list="$pid $(_descendants "$pid")"
    # shellcheck disable=SC2086
    kill -TERM $list 2>/dev/null
    sleep 2
    # shellcheck disable=SC2086
    kill -KILL $list 2>/dev/null
  ) >/dev/null 2>&1 &
  wd=$!
  { wait "$pid"; rc=$?; } 2>/dev/null
  # shellcheck disable=SC2046
  kill -KILL $(_descendants "$wd") "$wd" 2>/dev/null
  { wait "$wd"; } 2>/dev/null
  if [ -n "$flag" ] && [ -s "$flag" ]; then rm -f "$flag"; return 124; fi
  [ -z "$flag" ] || rm -f "$flag"
  return "$rc"
}

# --- bounded output -----------------------------------------------------------------------------------

# bounded_tail FILE N: at most N lines of FILE. When the file is longer, the first of them is an
# "(K earlier lines omitted)" marker followed by the last N-1 lines.
bounded_tail() {
  local f="$1" n="$2" total
  total="$(wc -l <"$f" | tr -d ' ')"
  if [ "$total" -le "$n" ]; then cat "$f"; return 0; fi
  if [ "$n" -lt 2 ]; then tail -n "$n" "$f"; return 0; fi
  printf '(%s earlier lines omitted)\n' "$((total - n + 1))"
  tail -n "$((n - 1))" "$f"
}

# report_failure GATE CHECK DIR LOGFILE TAIL_N CMD...: the failure report printed on stdout
#   <gate> check FAILED: <check>
#   $ (cd <dir> && <command>)
#   <bounded tail of the log>
report_failure() {
  local gate="$1" check="$2" dir="$3" log="$4" n="$5"; shift 5
  local rel="${dir#"${ROOT:-}"}"; rel="${rel#/}"; [ -n "$rel" ] || rel="."
  printf '%s check FAILED: %s\n' "$gate" "$check"
  printf '$ (cd %s && %s)\n' "$rel" "$*"
  bounded_tail "$log" "$n"
}

# log_timing GATE SECONDS STATUS: one line in .claude/.cache/gate-timing.log (never on stdout).
log_timing() {
  local d; d="$(cache_dir)"
  mkdir -p "$d" 2>/dev/null || return 0
  printf '%s gate=%s seconds=%s status=%s\n' "$(_iso_now)" "$1" "$2" "${3:--}" >>"$d/gate-timing.log" 2>/dev/null
  return 0
}

# log_has_missing_task LOGFILE: Gradle said a requested task does not exist (build still bootstrapping).
log_has_missing_task() { grep -Eq "Task '.*' not found" "$1" 2>/dev/null; }

# --- bypass ----------------------------------------------------------------------------------------------

# hook_bypass GATE [FILE]: returns 0 (caller must then exit 0) when HOOK_BYPASS is exactly 1 and the
# run is not CI; appends one trace line to .claude/.cache/bypass.log. Returns 1 otherwise.
hook_bypass() {
  [ "${HOOK_BYPASS:-}" = "1" ] || return 1
  [ "${CI:-}" = "true" ] || [ "${GITHUB_ACTIONS:-}" = "true" ] && return 1
  local d; d="$(cache_dir)"
  mkdir -p "$d" 2>/dev/null || return 0
  printf '%s gate=%s user=%s file=%s reason=%s\n' "$(_iso_now)" "$1" "${USER:-$(id -un 2>/dev/null)}" \
    "${2:--}" "${HOOK_BYPASS_REASON:--}" >>"$d/bypass.log" 2>/dev/null
  return 0
}

# --- repository walking ------------------------------------------------------------------------------------

# nearest_up DIR NAME...: walk up from DIR to $ROOT looking for any of NAME...; prints the directory.
nearest_up() {
  local dir="$1" n; shift
  while :; do
    for n in "$@"; do [ -e "$dir/$n" ] && { echo "$dir"; return 0; }; done
    [ "$dir" = "$ROOT" ] && return 1
    dir="$(dirname "$dir")"
  done
}

# changed_since_marker MARKER [--any]: relevant source files newer than MARKER (all of them when MARKER
# does not exist, i.e. a missing marker means a full run). With --any stops at the first hit (fast skip).
changed_since_marker() {
  local mark="$1" mode="${2:-}"
  local args=("$ROOT" \( -name node_modules -o -name build -o -name .gradle -o -name .claude \
      -o -name .specify -o -name dist \) -prune -o -type f
      \( -name '*.kt' -o -name '*.kts' -o -name '*.ts' -o -name '*.tsx' -o -name '*.toml'
      -o -name '*.json' -o -name '*.feature' -o -name '*.properties' -o -name '*.yml'
      -o -name '*.yaml' -o -name '*.sql' \))
  [ -n "$mark" ] && [ -f "$mark" ] && args+=(-newer "$mark")
  args+=(-print)
  [ "$mode" = "--any" ] && args+=(-quit)
  find "${args[@]}" 2>/dev/null
}
