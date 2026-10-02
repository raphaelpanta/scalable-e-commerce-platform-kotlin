#!/usr/bin/env bash
# US2 acceptance 2 and 4 / SC-002: the complete gate is skipped when nothing changed, and silent without a build.
. "$(dirname "$0")/lib.sh"
new_fixture

# marker newer than every source: silent, fast, zero tool invocations
set_marker_now
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent
assert_le "$T_ELAPSED" 1 "skip must finish in under 2 s"
assert_no_invocations
assert_matches "$T_ROOT/.claude/.cache/gate-timing.log" 'gate=stop seconds=[0-9]+ status=skip$'

# a relevant file changed after the marker: the gate runs
set_marker_recent
age_sources
touch "$T_ROOT/services/demo/domain/src/main/kotlin/demo/Price.kt"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent
assert_invoked "gradlew -q --console=plain verify"

# a non-source file (markdown) changed after the marker: still skipped
set_marker_now; : >"$FIXTURE_LOG"
echo notes >"$T_ROOT/NOTES.md"; touch -t 203101010000 "$T_ROOT/NOTES.md"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent; assert_no_invocations

# absent marker: full run
rm -f "$(marker_path)"; : >"$FIXTURE_LOG"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent
assert_invoked "gradlew -q --console=plain verify"
assert_invoked "npm run -s lint"

# no settings.gradle.kts and no package.json: silent no-op
rm -f "$(marker_path)" "$T_ROOT/settings.gradle.kts" "$T_ROOT/build.gradle.kts" "$T_ROOT/frontend/web/package.json"; : >"$FIXTURE_LOG"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent; assert_no_invocations
test_done
