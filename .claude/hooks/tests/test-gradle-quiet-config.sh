#!/usr/bin/env bash
# FR-013: quiet defaults for Gradle, test logging and npm, so tools add nothing to the context on success.
. "$(dirname "$0")/lib.sh"
has() { grep -qF -- "$2" "$REPO_ROOT/$1" || _t_fail "$1 must contain: $2"; }

has gradle.properties "org.gradle.console=plain"
has gradle.properties "org.gradle.logging.level=quiet"
has gradle.properties "org.gradle.warning.mode=none"

# test logging: feature 002 configures every Test task in its internal kotlin-base convention (applied by
# kotlin-domain, kotlin-application and kotlin-service), under the quiet log level that gradle.properties selects
TL="build-logic/src/main/kotlin/kotlin-base.gradle.kts"
has "$TL" "tasks.withType<Test>().configureEach"
has "$TL" "events(TestLogEvent.FAILED)"
has "$TL" "exceptionFormat = TestExceptionFormat.FULL"
has "$TL" "showStandardStreams = false"
grep -q 'TestLogEvent\.\(PASSED\|SKIPPED\|STARTED\|STANDARD_OUT\)' "$REPO_ROOT/$TL" && _t_fail "$TL must log failures only"

jq -e '.env.NPM_CONFIG_LOGLEVEL == "error" and .env.NO_COLOR == "1"
  and (.env.GRADLE_OPTS | contains("-Dorg.gradle.console=plain") and contains("-Dorg.gradle.logging.level=quiet"))' \
  "$REPO_ROOT/.claude/settings.json" >/dev/null || _t_fail ".claude/settings.json env lacks the quiet defaults"
has .npmrc "loglevel=error"
test_done
