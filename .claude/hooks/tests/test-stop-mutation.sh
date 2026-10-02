#!/usr/bin/env bash
# FR-004: mutation testing of changed code. `gradlew verify` (feature 002) already runs the full Pitest of
# every domain/application module, so the incremental step (Pitest on the changed domain/application classes
# only) is the fallback for a build without a `verify` task; "task not found" is a skip.
. "$(dirname "$0")/lib.sh"
new_fixture
D="$T_ROOT/services/demo/domain"

# verify exists: it covers mutation testing; no second, narrowed Pitest run and no task probe
age_sources; set_marker_recent
touch "$D/src/main/kotlin/demo/Price.kt"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent
assert_invoked "gradlew -q --console=plain verify"
assert_not_invoked "pitest"; assert_not_invoked "tasks --all"

# from here on the build has no `verify` task (the fallback path)
export FAKE_NOTFOUND_MATCH2='*console=plain verify*'
: >"$FIXTURE_LOG"
age_sources; set_marker_recent
touch "$D/src/main/kotlin/demo/Price.kt"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent
assert_invoked ":services:demo:domain:pitest -Pharness.mutation.classes=demo.Price*"
assert_invoked "tasks --all"

# only infrastructure changed: no Pitest (and no probe)
age_sources; set_marker_recent; : >"$FIXTURE_LOG"
touch "$T_ROOT/services/demo/infrastructure/src/main/kotlin/demo/Repo.kt"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent
assert_not_invoked "pitest"

# only a test file of domain changed: no Pitest
age_sources; set_marker_recent; : >"$FIXTURE_LOG"
touch "$D/src/test/kotlin/demo/PriceSpec.kt"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_not_invoked "pitest"

# a look-alike module inside build-logic (test fixtures) is not part of the build: no Pitest
FX="$T_ROOT/build-logic/src/test/resources/fixtures/demo/services/demo/domain"
mkdir -p "$FX/src/main/kotlin/demo"; echo '// pitest' >"$FX/build.gradle.kts"
printf 'package demo\n\nclass Age\n' >"$FX/src/main/kotlin/demo/Age.kt"
age_sources; set_marker_recent; : >"$FIXTURE_LOG"
touch "$FX/src/main/kotlin/demo/Age.kt"
HOOK_DRY_RUN=1 run_hook stop-full-check.sh "$(stop_json false)"
assert_not_contains stdout "demo.Age*"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_not_invoked "pitest -P"

# only markdown changed: gate skipped entirely
age_sources; set_marker_now; : >"$FIXTURE_LOG"
echo x >"$T_ROOT/services/demo/domain/README.md"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent; assert_no_invocations

# domain and application, several classes: one globbed pitest call per module
mkdir -p "$T_ROOT/services/demo/application/src/main/kotlin/demo/app"
echo '// application module' >"$T_ROOT/services/demo/application/build.gradle.kts"
printf 'package demo.app\n\nclass PlaceOrder\n' >"$T_ROOT/services/demo/application/src/main/kotlin/demo/app/PlaceOrder.kt"
printf 'package demo\n\nclass Order\n' >"$D/src/main/kotlin/demo/Order.kt"
export FAKE_TASKS=$':services:demo:domain:pitest - x\n:services:demo:application:pitest - x'
age_sources; set_marker_recent; : >"$FIXTURE_LOG"
touch "$D/src/main/kotlin/demo/Price.kt" "$D/src/main/kotlin/demo/Order.kt" \
      "$T_ROOT/services/demo/application/src/main/kotlin/demo/app/PlaceOrder.kt"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent
assert_invoked ":services:demo:domain:pitest -Pharness.mutation.classes=demo.Order*,demo.Price*"
assert_invoked ":services:demo:application:pitest -Pharness.mutation.classes=demo.app.PlaceOrder*"

# no project has a pitest task yet: the step is skipped, silently, and the gate stays green
export FAKE_TASKS=""
age_sources; set_marker_recent; : >"$FIXTURE_LOG"
touch "$D/src/main/kotlin/demo/Price.kt"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0; assert_silent
assert_not_invoked "pitest -P"

# Gradle reports "Task 'pitest' not found" (bootstrapping): skipped, not a failure
unset FAKE_TASKS
export FAKE_NOTFOUND_MATCH='*pitest*'
age_sources; set_marker_recent; : >"$FIXTURE_LOG"
touch "$D/src/main/kotlin/demo/Price.kt"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0 "pitest not found must be a skip"; assert_silent
assert_invoked "pitest -P"

# ... and the same for `verify` alone (the root lifecycle task of feature 002)
unset FAKE_NOTFOUND_MATCH
age_sources; set_marker_recent; : >"$FIXTURE_LOG"
touch "$D/src/main/kotlin/demo/Price.kt"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 0 "verify not found must be a skip"; assert_silent
[ "$(mtime_of "$(marker_path)")" -gt 1700000000 ] || _t_fail "a skipped step must not keep the marker stale"

# a real pitest failure of the fallback run (mutation score below threshold) blocks
unset FAKE_NOTFOUND_MATCH
export FAKE_FAIL_MATCH='*pitest*' FAKE_EXIT=1
age_sources; set_marker_recent
touch "$D/src/main/kotlin/demo/Price.kt"
run_hook stop-full-check.sh "$(stop_json false)"
assert_exit 2
assert_contains stderr "stop check FAILED: gradle:pitest"
test_done
