#!/usr/bin/env bash
# US1 acceptance 2: a failing check blocks (exit 2) with the check name, the exact command and a bounded tail.
. "$(dirname "$0")/lib.sh"
new_fixture
export FAKE_EXIT=1 FAKE_OUTPUT_LINES=200
run_hook post-edit-check.sh "$(post_edit_json "$T_ROOT/services/demo/domain/src/main/kotlin/demo/Price.kt")"
assert_exit 2
assert_contains stderr "post-edit check FAILED: gradle"
assert_matches stderr '^\$ \(cd \. && .*gradlew -q --console=plain :services:demo:domain:ktlintCheck :services:demo:domain:detekt :services:demo:domain:test --tests \*\.PriceSpec\)$'
assert_contains stderr "fake gradlew output line 200"
assert_not_contains stderr "output line 100"   # only the tail is shown
assert_contains stderr "earlier lines omitted)"
assert_max_lines stderr 62   # header + command + at most 60 log lines
[ ! -s "$T_OUT/stdout" ] || _t_fail "stdout must stay empty on failure"
test_done
