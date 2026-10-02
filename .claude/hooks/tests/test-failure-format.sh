#!/usr/bin/env bash
# FR-006: a failing check shows its name, a `$ ` command line, an omitted-lines marker and a bounded tail
# (<= 60 log lines in the per-file gate, <= 40 in the complete gate).
. "$(dirname "$0")/lib.sh"
new_fixture
export FAKE_EXIT=1 FAKE_OUTPUT_LINES=500

run_hook post-edit-check.sh "$(post_edit_json "$T_ROOT/services/demo/domain/src/main/kotlin/demo/Price.kt")"
assert_exit 2
assert_matches stderr '^post-edit check FAILED: gradle$'
assert_matches stderr '^\$ \(cd \. && '
assert_matches stderr '^\([0-9]+ earlier lines omitted\)$'
assert_contains stderr "fake gradlew output line 500"
n="$(grep -c 'fake gradlew output line' "$T_OUT/stderr")"
assert_le "$n" 60 "log lines in the per-file report"
assert_max_lines stderr 62
[ "$n" -ge 50 ] || _t_fail "the per-file report should still show a useful tail, got $n lines"

run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 2
assert_matches stderr '^stop check FAILED: gradle:verify$'
assert_matches stderr '^\$ \(cd \. && .*verify\)$'
assert_matches stderr '^\([0-9]+ earlier lines omitted\)$'
assert_contains stderr "fake gradlew output line 500"
# every failed check is bounded on its own
for label in gradle:verify web:lint web:test web:stryker; do assert_contains stderr "stop check FAILED: $label"; done
checks="$(grep -c '^stop check FAILED' "$T_OUT/stderr")"
total="$(grep -c 'fake .* output line' "$T_OUT/stderr")"
assert_le "$total" $((40 * checks)) "at most 40 log lines per check in the complete gate"
test_done
