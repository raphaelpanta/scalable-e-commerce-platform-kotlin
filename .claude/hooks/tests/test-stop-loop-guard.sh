#!/usr/bin/env bash
# US2 acceptance 3 / FR-008 / SC-004: never two blocks in a row; the second attempt reports in a systemMessage.
. "$(dirname "$0")/lib.sh"
new_fixture
export FAKE_EXIT=1 FAKE_OUTPUT_LINES=3 FAKE_FAIL_MATCH='*verify*'

run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 2 "first attempt blocks"
assert_contains stderr "stop check FAILED: gradle:verify"
run_hook stop-full-check.sh "$(stop_json true)"
assert_exit 0 "stop_hook_active=true must not block"
[ ! -s "$T_OUT/stderr" ] || _t_fail "second attempt must not write to stderr"
jq -e '.systemMessage | contains("stop check FAILED: gradle:verify") and contains("fake gradlew output line 3")' "$T_OUT/stdout" >/dev/null ||
  _t_fail "systemMessage must carry the same report: $(cat "$T_OUT/stdout")"

run_hook stop-full-check.sh "$(stop_json true)"
assert_exit 0 "still not blocking while stop_hook_active stays true"

# a later, fresh attempt blocks again (the guard only covers one cycle)
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 2
test_done
