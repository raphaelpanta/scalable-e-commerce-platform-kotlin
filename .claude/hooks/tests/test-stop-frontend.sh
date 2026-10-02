#!/usr/bin/env bash
# US2 acceptance 5 / FR-011: frontend lint/test non-interactively; Stryker only when configured.
. "$(dirname "$0")/lib.sh"
new_fixture
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent
assert_invoked "npm run -s lint #CI=1 #stdin=null #cwd=web"
assert_invoked "npm run -s test #CI=1 #stdin=null #cwd=web"
assert_invoked "npx --no-install stryker run --incremental #CI=1 #stdin=null #cwd=web"

# no stryker config: no Stryker run
rm -f "$T_ROOT/frontend/web/stryker.config.json"; rm -f "$(marker_path)"; : >"$FIXTURE_LOG"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent
assert_invoked "npm run -s lint"
assert_not_invoked "stryker"

# a package without lint/test scripts is skipped
mkdir -p "$T_ROOT/frontend/lib"; echo '{ "name": "lib", "scripts": {} }' >"$T_ROOT/frontend/lib/package.json"
rm -f "$(marker_path)"; : >"$FIXTURE_LOG"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent
assert_not_invoked "#cwd=lib"

# failing frontend test blocks and names the package script
export FAKE_EXIT=1 FAKE_FAIL_MATCH='*run -s test*'
rm -f "$(marker_path)"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 2
assert_contains stderr "stop check FAILED: web:test"
assert_matches stderr '^\$ \(cd frontend/web && npm run -s test\)$'

# the root package frontend/package.json is already linted and tested by `gradlew verify` (feature 002's
# frontendCheck), so the hook does not run it a second time; without a Gradle build the hook runs it itself
unset FAKE_EXIT FAKE_FAIL_MATCH
rm -rf "$T_ROOT/frontend/lib"
echo '{ "name": "storefront", "scripts": { "lint": "eslint .", "test": "vitest" } }' >"$T_ROOT/frontend/package.json"
rm -f "$(marker_path)"; : >"$FIXTURE_LOG"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent
assert_invoked "gradlew -q --console=plain verify"
assert_invoked "npm run -s lint #CI=1 #stdin=null #cwd=web"
assert_not_invoked "#cwd=frontend"
rm -f "$T_ROOT/settings.gradle.kts" "$T_ROOT/build.gradle.kts" "$(marker_path)"; : >"$FIXTURE_LOG"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent
assert_invoked "npm run -s lint #CI=1 #stdin=null #cwd=frontend"
assert_invoked "npm run -s test #CI=1 #stdin=null #cwd=frontend"
test_done
