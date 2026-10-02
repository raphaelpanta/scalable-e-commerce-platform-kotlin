# shellcheck shell=bash
# Shared helpers for the hook tests: a throw-away copy of the fixture monorepo per test, hook-JSON
# builders, hook runners and assertions. Source it from a test: . "$(dirname "$0")/lib.sh"
# A test calls new_fixture, runs hooks with run_hook, asserts, then ends with test_done.

T_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOKS_DIR="$(cd "$T_DIR/.." && pwd)"
# shellcheck disable=SC2034  # available to tests that source this library
REPO_ROOT="$(cd "$HOOKS_DIR/../.." && pwd)"
FIXTURE_SRC="$T_DIR/fixtures/monorepo"
FIXBIN="$T_DIR/fixtures/bin"
T_NAME="$(basename "${0:-test}")"
T_FAILS=0
T_TMPS=""
T_ROOT=""; T_OUT=""; RC=0

_t_cleanup() { local d; for d in $T_TMPS; do rm -rf "$d"; done; }
trap _t_cleanup EXIT

# new_fixture: fresh copy of the fixture monorepo in $T_ROOT, outputs/logs in $T_OUT, a PATH that
# contains only the fake npm/npx, coreutils and jq (so a real gradle can never be picked up).
new_fixture() {
  local base; base="$(mktemp -d "${TMPDIR:-/tmp}/hooktest.XXXXXX")"
  base="$(cd "$base" && pwd -P)"
  T_TMPS="$T_TMPS $base"
  T_ROOT="$base/repo"; T_OUT="$base/out"
  mkdir -p "$T_ROOT" "$T_OUT/tools"
  cp -R "$FIXTURE_SRC/." "$T_ROOT/"
  # `build/` is git-ignored in the real repository, so the generated file is (re)created here.
  mkdir -p "$T_ROOT/services/demo/domain/build"
  [ -f "$T_ROOT/services/demo/domain/build/Gen.kt" ] ||
    printf 'package demo\n\nclass Gen\n' >"$T_ROOT/services/demo/domain/build/Gen.kt"
  ln -s "$(command -v jq)" "$T_OUT/tools/jq"
  FIXTURE_LOG="$T_OUT/calls.log"; : >"$FIXTURE_LOG"
  export FIXTURE_LOG
  export CLAUDE_PROJECT_DIR="$T_ROOT"
  export PATH="$FIXBIN:$T_OUT/tools:/usr/bin:/bin:/usr/sbin:/sbin"
  unset HOOK_DRY_RUN HOOK_BYPASS HOOK_BYPASS_REASON HOOK_BUDGET_SECONDS CI GITHUB_ACTIONS
  unset FAKE_SLEEP FAKE_EXIT FAKE_OUTPUT_LINES FAKE_FAIL_MATCH FAKE_NOTFOUND_MATCH FAKE_NOTFOUND_MATCH2 FAKE_TASKS
}

# JSON builders
post_edit_json() { jq -cn --arg f "$1" '{tool_name:"Write",tool_input:{file_path:$f},tool_response:{filePath:$f}}'; }
stop_json() { jq -cn --argjson a "${1:-false}" '{hook_event_name:"Stop",stop_hook_active:$a}'; }

# run_hook SCRIPT JSON [OUT_PREFIX]: runs .claude/hooks/SCRIPT with JSON on stdin; sets RC, T_ELAPSED
# and leaves stdout/stderr in $T_OUT/<prefix>stdout and <prefix>stderr (prefix defaults to empty).
run_hook() {
  local script="$1" json="$2" p="${3:-}" t0
  t0="$(date +%s)"
  printf '%s' "$json" | "$HOOKS_DIR/$script" >"$T_OUT/${p}stdout" 2>"$T_OUT/${p}stderr"
  RC=$?
  # shellcheck disable=SC2034  # read by tests after run_hook
  T_ELAPSED=$(( $(date +%s) - t0 ))
}

_t_file() { case "$1" in stdout|stderr) echo "$T_OUT/$1" ;; *) echo "$1" ;; esac; }
_t_fail() { T_FAILS=$((T_FAILS + 1)); echo "  ASSERT FAILED ($T_NAME): $*"; }

assert_exit() { [ "$RC" -eq "$1" ] || _t_fail "exit code $RC, expected $1${2:+ ($2)}"; }
assert_silent() {
  local so se; so="$(wc -c <"$T_OUT/${1:-}stdout" | tr -d ' ')"; se="$(wc -c <"$T_OUT/${1:-}stderr" | tr -d ' ')"
  [ "$so" -eq 0 ] && [ "$se" -eq 0 ] || _t_fail "expected zero output bytes, got stdout=$so stderr=$se: $(head -c 300 "$T_OUT/${1:-}stdout" "$T_OUT/${1:-}stderr")"
}
assert_contains() { grep -qF -- "$2" "$(_t_file "$1")" || _t_fail "$1 does not contain: $2"; }
assert_not_contains() { ! grep -qF -- "$2" "$(_t_file "$1")" || _t_fail "$1 unexpectedly contains: $2"; }
assert_matches() { grep -qE -- "$2" "$(_t_file "$1")" || _t_fail "$1 does not match: $2"; }
assert_max_lines() { # FILE N
  local n; n="$(wc -l <"$(_t_file "$1")" | tr -d ' ')"
  [ "$n" -le "$2" ] || _t_fail "$1 has $n lines, max $2"
}
assert_invoked() { grep -qF -- "$1" "$FIXTURE_LOG" || _t_fail "tool not invoked with: $1 (calls: $(tr '\n' '|' <"$FIXTURE_LOG"))"; }
assert_not_invoked() { ! grep -qF -- "$1" "$FIXTURE_LOG" || _t_fail "tool unexpectedly invoked with: $1"; }
assert_no_invocations() { [ ! -s "$FIXTURE_LOG" ] || _t_fail "expected zero tool invocations, got: $(tr '\n' '|' <"$FIXTURE_LOG")"; }
assert_file() { [ -e "$1" ] || _t_fail "missing file: $1"; }
assert_no_file() { [ ! -e "$1" ] || _t_fail "unexpected file: $1"; }
assert_le() { [ "$1" -le "$2" ] || _t_fail "$1 is not <= $2${3:+ ($3)}"; }

# Marker helpers: set_marker_now (2030, newer than everything), set_marker_past (2000), set_marker_recent (2020).
marker_path() { echo "$T_ROOT/.claude/.cache/last-full-check"; }
set_marker_now() { mkdir -p "$T_ROOT/.claude/.cache"; touch -t 203001010000 "$(marker_path)"; }
set_marker_past() { mkdir -p "$T_ROOT/.claude/.cache"; touch -t 200001010000 "$(marker_path)"; }

test_done() {
  if [ "$T_FAILS" -ne 0 ]; then echo "$T_NAME: $T_FAILS assertion(s) failed"; exit 1; fi
  exit 0
}

# age_sources: make every file of the fixture look old (2019) so that only files touched afterwards
# count as "changed since the marker"; use together with set_marker_past (marker dated 2000)... or
# set_marker_recent (marker dated 2020).
age_sources() { find "$T_ROOT" -type f -not -path '*/.claude/*' -exec touch -t 201901010000 {} +; }
set_marker_recent() { mkdir -p "$T_ROOT/.claude/.cache"; touch -t 202001010000 "$(marker_path)"; }
mtime_of() { stat -c %Y "$1" 2>/dev/null || stat -f %m "$1"; }   # GNU first: GNU `stat -f` means --file-system
