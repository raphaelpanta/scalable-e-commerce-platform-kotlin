#!/usr/bin/env bash
# FR-007 / SC-003: every passing scenario of both hooks yields exactly zero bytes on stdout and stderr
# (HOOK_DRY_RUN unset), even when the tools underneath are noisy.
. "$(dirname "$0")/lib.sh"
new_fixture
export FAKE_OUTPUT_LINES=300     # noisy but passing tools
D="$T_ROOT/services/demo/domain"

pe() { run_hook post-edit-check.sh "$(post_edit_json "$1")"; assert_exit 0 "$1"; assert_silent; }
pe "$D/src/main/kotlin/demo/Price.kt"
pe "$D/src/test/kotlin/demo/PriceSpec.kt"
pe "$T_ROOT/services/demo/infrastructure/src/main/kotlin/demo/Repo.kt"
pe "$D/build.gradle.kts"
pe "$T_ROOT/frontend/web/src/index.ts"
pe "$D/build/Gen.kt"                                   # ignored file
echo hi >"$T_ROOT/README.md"; pe "$T_ROOT/README.md"   # not a source file
pe "$D/src/main/kotlin/demo/DoesNotExist.kt"           # nonexistent
[ -s "$FIXTURE_LOG" ] || _t_fail "the noisy scenarios must actually have run the tools"

# complete gate: green run, then skipped run, then no-build repository
run_hook stop-full-check.sh "$(stop_json false)"; assert_exit 0; assert_silent
run_hook stop-full-check.sh "$(stop_json false)"; assert_exit 0; assert_silent   # skip (marker fresh)
run_hook stop-full-check.sh "$(stop_json true)";  assert_exit 0; assert_silent
rm -f "$(marker_path)" "$T_ROOT/settings.gradle.kts" "$T_ROOT/build.gradle.kts" "$T_ROOT/frontend/web/package.json"
run_hook stop-full-check.sh "$(stop_json false)"; assert_exit 0; assert_silent

# T056: bookkeeping failures (read-only cache directory) must not leak stderr either
chmod a-w "$T_ROOT/.claude/.cache" 2>/dev/null
pe "$D/src/main/kotlin/demo/Price.kt"
run_hook stop-full-check.sh "$(stop_json false)"; assert_exit 0; assert_silent
chmod u+w "$T_ROOT/.claude/.cache" 2>/dev/null

# a tool that is missing from the repository is silent too
rm -f "$T_ROOT/gradlew"
pe "$D/src/main/kotlin/demo/Price.kt"
test_done
