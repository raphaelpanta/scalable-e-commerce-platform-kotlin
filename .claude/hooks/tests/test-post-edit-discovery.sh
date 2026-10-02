#!/usr/bin/env bash
# US1 acceptance 3 / FR-002: covering tests are found by naming convention.
. "$(dirname "$0")/lib.sh"
new_fixture
D="$T_ROOT/services/demo/domain"
PRICE="$D/src/main/kotlin/demo/Price.kt"

# Price.kt -> its Kotest spec only
run_hook post-edit-check.sh "$(post_edit_json "$PRICE")"
assert_exit 0
assert_invoked ":services:demo:domain:test --tests *.PriceSpec"
assert_invoked ":services:demo:domain:ktlintCheck"
assert_invoked ":services:demo:domain:detekt"

# Repo.kt has no covering test: style tasks only, no :test, no failure
: >"$FIXTURE_LOG"
run_hook post-edit-check.sh "$(post_edit_json "$T_ROOT/services/demo/infrastructure/src/main/kotlin/demo/Repo.kt")"
assert_exit 0; assert_silent
assert_invoked ":services:demo:infrastructure:ktlintCheck"
assert_not_invoked ":test"

# *Spec, *Test, *PropertyTest and other source sets are all discovered
mkdir -p "$D/src/integrationTest/kotlin/demo"
echo 'package demo
class PriceTest' >"$D/src/test/kotlin/demo/PriceTest.kt"
echo 'package demo
class PricePropertyTest' >"$D/src/test/kotlin/demo/PricePropertyTest.kt"
echo 'package demo
class PriceRoundingIntegrationTest' >"$D/src/integrationTest/kotlin/demo/PriceRoundingIntegrationTest.kt"
: >"$FIXTURE_LOG"
run_hook post-edit-check.sh "$(post_edit_json "$PRICE")"
assert_exit 0
for c in PriceSpec PriceTest PricePropertyTest PriceRoundingIntegrationTest; do assert_invoked "--tests *.$c"; done

# editing a test file runs that class
: >"$FIXTURE_LOG"
run_hook post-edit-check.sh "$(post_edit_json "$D/src/test/kotlin/demo/PriceTest.kt")"
assert_exit 0
assert_invoked ":services:demo:domain:test --tests *.PriceTest"
assert_not_invoked "--tests *.PriceSpec"

# no more than 20 classes are selected
for i in $(seq 1 30); do printf 'package demo\nclass Price%s\n' "$i" >"$D/src/test/kotlin/demo/PriceX${i}Test.kt"; done
: >"$FIXTURE_LOG"
run_hook post-edit-check.sh "$(post_edit_json "$PRICE")"
n="$(grep -o -- '--tests' "$FIXTURE_LOG" | wc -l | tr -d ' ')"
assert_le "$n" 20 "at most 20 test classes"
test_done
