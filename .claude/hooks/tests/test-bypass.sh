#!/usr/bin/env bash
# FR-019: HOOK_BYPASS=1 skips a gate for one run, silently, and leaves a trace in .claude/.cache/bypass.log.
. "$(dirname "$0")/lib.sh"
new_fixture
PRICE="$T_ROOT/services/demo/domain/src/main/kotlin/demo/Price.kt"
REL="services/demo/domain/src/main/kotlin/demo/Price.kt"
LOGF="$T_ROOT/.claude/.cache/bypass.log"
lines() { [ -f "$LOGF" ] && wc -l <"$LOGF" | tr -d ' ' || echo 0; }

# per-file gate, even though the tools would fail
export FAKE_EXIT=1
HOOK_BYPASS=1 run_hook post-edit-check.sh "$(post_edit_json "$PRICE")"
assert_exit 0; assert_silent; assert_no_invocations
[ "$(lines)" -eq 1 ] || _t_fail "exactly one bypass line expected, got $(lines)"
assert_matches "$LOGF" "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z gate=post-edit user=[^ ]+ file=$REL reason=-\$"

HOOK_BYPASS=1 HOOK_BYPASS_REASON="spike, red on purpose" run_hook post-edit-check.sh "$(post_edit_json "$PRICE")"
assert_exit 0; assert_silent
assert_contains "$LOGF" "gate=post-edit user="
assert_contains "$LOGF" "reason=spike, red on purpose"
[ "$(lines)" -eq 2 ] || _t_fail "second bypass must append a second line"

# complete gate
HOOK_BYPASS=1 run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent; assert_no_invocations
assert_matches "$LOGF" ' gate=stop user=[^ ]+ file=- reason=-$'
[ "$(lines)" -eq 3 ] || _t_fail "stop bypass must append a line"
assert_no_file "$(marker_path)"   # a bypass is not a green run

# values other than 1 do not bypass
for v in true 0 yes ""; do
  : >"$FIXTURE_LOG"
  HOOK_BYPASS="$v" run_hook post-edit-check.sh "$(post_edit_json "$PRICE")"
  assert_exit 2 "HOOK_BYPASS='$v' must not bypass"
  assert_invoked "gradlew"
done
[ "$(lines)" -eq 3 ] || _t_fail "non-bypass values must not log"

# a second run without the variable runs the checks again
: >"$FIXTURE_LOG"
run_hook post-edit-check.sh "$(post_edit_json "$PRICE")"
assert_exit 2; assert_invoked "gradlew"

# CI ignores the bypass
for ci in "CI=true" "GITHUB_ACTIONS=true"; do
  : >"$FIXTURE_LOG"
  env "$ci" HOOK_BYPASS=1 bash -c 'printf "%s" "$1" | "$2"/post-edit-check.sh' _ "$(post_edit_json "$PRICE")" "$HOOKS_DIR" >"$T_OUT/stdout" 2>"$T_OUT/stderr"
  RC=$?
  assert_exit 2 "$ci must ignore the bypass"; assert_invoked "gradlew"
  : >"$FIXTURE_LOG"
  env "$ci" HOOK_BYPASS=1 bash -c 'printf "%s" "$1" | "$2"/stop-full-check.sh' _ "$(stop_json false)" "$HOOKS_DIR" >"$T_OUT/stdout" 2>"$T_OUT/stderr"
  # shellcheck disable=SC2034  # read by assert_exit
  RC=$?
  assert_exit 2 "$ci must ignore the bypass (stop)"; assert_invoked "gradlew"
done
[ "$(lines)" -eq 3 ] || _t_fail "CI runs must not log a bypass"

# dry run is evaluated before the bypass: commands are still listed, nothing is logged
HOOK_DRY_RUN=1 HOOK_BYPASS=1 run_hook post-edit-check.sh "$(post_edit_json "$PRICE")"
assert_exit 0; assert_contains stdout "DRY ["
[ "$(lines)" -eq 3 ] || _t_fail "dry run must not log a bypass"
test_done
