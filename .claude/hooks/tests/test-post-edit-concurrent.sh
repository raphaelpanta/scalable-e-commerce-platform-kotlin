#!/usr/bin/env bash
# Edge case: simultaneous edits report independently (distinct temp logs, no cross-contamination).
. "$(dirname "$0")/lib.sh"
new_fixture
export FAKE_SLEEP=2 FAKE_OUTPUT_LINES=3 FAKE_FAIL_MATCH='*infrastructure*' FAKE_EXIT=1
J1="$(post_edit_json "$T_ROOT/services/demo/domain/src/main/kotlin/demo/Price.kt")"
J2="$(post_edit_json "$T_ROOT/services/demo/infrastructure/src/main/kotlin/demo/Repo.kt")"
printf '%s' "$J1" | "$HOOKS_DIR/post-edit-check.sh" >"$T_OUT/pass.out" 2>"$T_OUT/pass.err" & p1=$!
printf '%s' "$J2" | "$HOOKS_DIR/post-edit-check.sh" >"$T_OUT/fail.out" 2>"$T_OUT/fail.err" & p2=$!
wait $p1; rc1=$?
wait $p2; rc2=$?
[ "$rc1" -eq 0 ] || _t_fail "passing invocation returned $rc1"
[ "$rc2" -eq 2 ] || _t_fail "failing invocation returned $rc2"
[ ! -s "$T_OUT/pass.out" ] && [ ! -s "$T_OUT/pass.err" ] || _t_fail "passing invocation was not silent: $(cat "$T_OUT/pass.err")"
assert_contains "$T_OUT/fail.err" "services:demo:infrastructure"
assert_not_contains "$T_OUT/fail.err" "services:demo:domain"
n="$(grep -c 'fake gradlew output line' "$T_OUT/fail.err")"
[ "$n" -eq 3 ] || _t_fail "failing report should contain exactly its own 3 log lines, got $n"
test_done
