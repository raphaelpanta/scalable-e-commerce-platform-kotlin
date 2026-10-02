#!/usr/bin/env bash
# US1 acceptance 1: a passing edit is silent (zero bytes on stdout and stderr).
. "$(dirname "$0")/lib.sh"
new_fixture
run_hook post-edit-check.sh "$(post_edit_json "$T_ROOT/services/demo/domain/src/main/kotlin/demo/Price.kt")"
assert_exit 0
assert_silent
assert_invoked ":services:demo:domain:test --tests *.PriceSpec"
# T022: the run is timed in .claude/.cache/gate-timing.log (nothing on stdout)
assert_matches "$T_ROOT/.claude/.cache/gate-timing.log" 'gate=post-edit seconds=[0-9]+ status=pass$'
# T023: the harness timeout leaves room for the internal 300 s budget and its report
jq -e '.hooks.PostToolUse[0].hooks[0].timeout == 330' "$REPO_ROOT/.claude/settings.json" >/dev/null ||
  _t_fail "PostToolUse timeout in .claude/settings.json must be 330"
test_done
