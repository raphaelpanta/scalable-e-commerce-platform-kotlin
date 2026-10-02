#!/usr/bin/env bash
# FR-012: a check exceeding the budget is aborted, reported as TIMEOUT, and leaves no process behind.
. "$(dirname "$0")/lib.sh"
new_fixture
export FAKE_SLEEP=33 HOOK_BUDGET_SECONDS=2
run_hook post-edit-check.sh "$(post_edit_json "$T_ROOT/services/demo/domain/src/main/kotlin/demo/Price.kt")"
assert_exit 2
assert_contains stderr "TIMEOUT after 2s"
assert_contains stderr "post-edit check FAILED: gradle"
assert_contains stderr "\$ (cd . && "
assert_le "$T_ELAPSED" 8 "the hook must return soon after the budget"
sleep 1
if pgrep -f "$T_ROOT/gradlew" >/dev/null 2>&1 || pgrep -f 'sleep 33$' >/dev/null 2>&1; then
  _t_fail "shim process still running after the timeout"; pkill -f 'sleep 33$' 2>/dev/null
fi
test_done
