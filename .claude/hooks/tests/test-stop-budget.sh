#!/usr/bin/env bash
# FR-012: the complete gate shares one deadline across checks, reports TIMEOUT and leaves no process behind.
. "$(dirname "$0")/lib.sh"
new_fixture
export FAKE_SLEEP=34 HOOK_BUDGET_SECONDS=3
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 2
assert_contains stderr "gradle:verify: TIMEOUT after 3s"
assert_contains stderr "not run: time budget exhausted"
assert_le "$T_ELAPSED" 10 "total elapsed must stay within the shared deadline (3 s) plus kill grace"
assert_not_invoked "npm run"          # the budget was spent by the first check
assert_no_file "$(marker_path)"
sleep 1
if pgrep -f "$T_ROOT/gradlew" >/dev/null 2>&1 || pgrep -f 'sleep 34$' >/dev/null 2>&1; then
  _t_fail "shim process still running after the timeout"; pkill -f 'sleep 34$' 2>/dev/null
fi
assert_matches "$T_ROOT/.claude/.cache/gate-timing.log" 'gate=stop seconds=[0-9]+ status=fail$'
test_done
