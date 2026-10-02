#!/usr/bin/env bash
# US2 acceptance 1 / FR-005: a failing check blocks once and keeps the marker; a green run refreshes it.
. "$(dirname "$0")/lib.sh"
new_fixture
set_marker_past
before="$(mtime_of "$(marker_path)")"

export FAKE_FAIL_MATCH='*console=plain verify*' FAKE_EXIT=1 FAKE_OUTPUT_LINES=100
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 2
assert_contains stderr "Full quality check FAILED"
assert_contains stderr "stop check FAILED: gradle:verify"
assert_matches stderr '^\$ \(cd \. && .*gradlew -q --console=plain verify\)$'
assert_contains stderr "earlier lines omitted)"
[ "$(mtime_of "$(marker_path)")" = "$before" ] || _t_fail "marker must stay unchanged on failure"
assert_no_file "$(marker_path).new"
[ ! -s "$T_OUT/stdout" ] || _t_fail "stdout must stay empty when blocking"
# a failing step does not stop the other checks from running (one consolidated report)
assert_invoked "npm run -s lint"

# green run: silent, marker refreshed
unset FAKE_FAIL_MATCH FAKE_EXIT FAKE_OUTPUT_LINES
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent
after="$(mtime_of "$(marker_path)")"
[ "$after" -gt "$before" ] || _t_fail "marker must be refreshed after a green run"
assert_no_file "$(marker_path).new"
assert_matches "$T_ROOT/.claude/.cache/gate-timing.log" 'gate=stop seconds=[0-9]+ status=pass$'

# T036: the harness timeout leaves room for the internal 1200 s budget
jq -e '.hooks.Stop[0].hooks[0].timeout == 1230' "$REPO_ROOT/.claude/settings.json" >/dev/null ||
  _t_fail "Stop timeout in .claude/settings.json must be 1230"
test_done
