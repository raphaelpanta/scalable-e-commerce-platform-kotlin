#!/usr/bin/env bash
# FR-011: TypeScript edits run eslint, tsc, vitest related (CI=1, non-interactive); prettier only with a config.
. "$(dirname "$0")/lib.sh"
new_fixture
TS="$T_ROOT/frontend/web/src/index.ts"
run_hook post-edit-check.sh "$(post_edit_json "$TS")"
assert_exit 0; assert_silent
assert_invoked "npx --no-install eslint --max-warnings=0 $TS"
assert_invoked "npx --no-install tsc --noEmit -p $T_ROOT/frontend/web"
assert_invoked "npx --no-install vitest related $TS --run --passWithNoTests"
assert_not_invoked "prettier"
assert_not_invoked "#CI=#"          # CI must be set for every call
assert_not_invoked "#stdin=other"   # stdin closed for every call
assert_invoked "#CI=1 #stdin=null"

echo '{}' >"$T_ROOT/frontend/web/.prettierrc"
: >"$FIXTURE_LOG"
run_hook post-edit-check.sh "$(post_edit_json "$TS")"
assert_exit 0; assert_silent
assert_invoked "npx --no-install prettier --check $TS"

# a failing eslint blocks and names itself
: >"$FIXTURE_LOG"
export FAKE_EXIT=1 FAKE_FAIL_MATCH='*eslint*'
run_hook post-edit-check.sh "$(post_edit_json "$TS")"
assert_exit 2
assert_contains stderr "post-edit check FAILED: eslint"
assert_not_invoked "tsc"
test_done
