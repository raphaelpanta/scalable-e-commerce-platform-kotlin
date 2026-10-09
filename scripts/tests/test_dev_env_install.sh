#!/usr/bin/env bash
# dev-env --install (T065): without it missing tools are only reported; with it every install is announced as
# `INSTALL <tool>: <command>`, uses brew on macOS and apt-get/dnf on Linux, never `brew install --cask docker`,
# the JDK through `sdk env install`; sudo only after the exact prompt answered y, --yes never answers it,
# non-interactive prints `manual: sudo <command>`; re-check after the installs; a remaining FAIL exits 3.
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"
source "$(dirname "$0")/lib/dev_env_fixture.sh"

test_case "without --install nothing is installed; fix lines only"
dev_env_fixture
STUB_ABSENT="jq gitleaks" assert_exit_code 3 dev_env init
assert_contains "$LAST_OUT" "fix (macos): brew install jq" "fix line"
assert_not_contains "$LAST_OUT" "INSTALL" "no install announced"
assert_eq "" "$(stub_log_grep '^(brew|sudo|apt-get|dnf|sdk) ')" "no installer ran"
assert_file_missing "$ENV_FILE" "not configured"

test_case "macOS, interactive: brew installs are announced and run, then re-checked, then the clone is configured"
dev_env_fixture
STUB_ABSENT="jq gitleaks java" assert_exit_code 0 dev_env_in "" init --install
out="$LAST_OUT"
assert_contains "$out" "INSTALL jdk: sdk env install" "jdk announced"
assert_contains "$out" "INSTALL gitleaks: brew install gitleaks" "gitleaks announced"
assert_contains "$out" "INSTALL jq: brew install jq" "jq announced"
assert_eq "1" "$(stub_log_count '^sdk env install$')" "sdk ran"
assert_eq "1" "$(stub_log_count '^brew install gitleaks$')" "brew gitleaks ran"
assert_eq "1" "$(stub_log_count '^brew install jq$')" "brew jq ran"
assert_contains "$out" "re-checking: jdk gitleaks jq" "re-check announced"
assert_contains "$out" "checks: 3 total, 3 PASS, 0 FAIL, 0 SKIP" "re-check passes"
assert_contains "$out" "CHANGE  env " "configuration followed"
announce_line="$(printf '%s\n' "$out" | grep -n 'INSTALL jq' | cut -d: -f1)"
recheck_line="$(printf '%s\n' "$out" | grep -n 're-checking' | cut -d: -f1)"
[ "$announce_line" -lt "$recheck_line" ] || _fail "announcement must precede the re-check"
assert_eq "" "$(stub_log_grep '^sudo')" "no sudo on macOS"

test_case "macOS: the engine is a printed decision, never a cask install"
dev_env_fixture
STUB_ENGINE=none assert_exit_code 3 dev_env init --install --yes
assert_contains "$LAST_OUT" "decision: install Docker Desktop (https://docs.docker.com/desktop/) or run: brew install podman && podman machine init && podman machine start" "decision printed"
assert_not_contains "$LAST_OUT" "INSTALL engine" "engine not installed"
assert_eq "" "$(stub_log_grep '^brew install --cask')" "no cask"
assert_eq "" "$(stub_log_grep '^brew install podman')" "podman not installed silently"
assert_contains "$LAST_OUT" "ERROR: prerequisites still missing; nothing was configured" "still failing"

test_case "node and npm missing install one package (node@24) once"
dev_env_fixture
STUB_ABSENT="node npm" assert_exit_code 0 dev_env init --install --yes
assert_eq "1" "$(stub_log_count '^brew install node@24$')" "one brew call"
assert_eq "1" "$(count_lines "$LAST_OUT" 'INSTALL node: brew install node@24')" "announced once"

test_case "Linux (apt): sudo only after the exact prompt answered y; the stub records the run"
dev_env_fixture
STUB_OS=Linux STUB_ABSENT="jq" assert_exit_code 0 dev_env_in "y" init --install
assert_contains "$LAST_OUT" "INSTALL jq: sudo apt-get install -y jq" "announced"
assert_contains "$LAST_OUT" "Run with elevated privileges? sudo apt-get install -y jq [y/N]:" "exact prompt"
assert_eq "1" "$(stub_log_count '^sudo apt-get install -y jq$')" "sudo ran once"
assert_contains "$LAST_OUT" "PASS  jq " "re-check passes"

test_case "Linux (apt): answering anything but y skips the elevated command"
dev_env_fixture
STUB_OS=Linux STUB_ABSENT="jq" assert_exit_code 3 dev_env_in "n" init --install
assert_contains "$LAST_OUT" "skipped: sudo apt-get install -y jq" "skipped"
assert_eq "" "$(stub_log_grep '^sudo')" "sudo never ran"

test_case "Linux (apt): --yes never answers the sudo prompt; without a terminal the command is a manual step"
dev_env_fixture
STUB_OS=Linux STUB_ABSENT="jq curl" assert_exit_code 3 dev_env init --install --yes
assert_contains "$LAST_OUT" "manual: sudo apt-get install -y jq" "manual jq"
assert_contains "$LAST_OUT" "manual: sudo apt-get install -y curl" "manual curl"
assert_eq "" "$(stub_log_grep '^sudo')" "sudo never ran"
assert_not_contains "$LAST_OUT" "[y/N]" "no prompt without a terminal"

test_case "Linux (apt): gitleaks has no package, so it is a printed manual step; the JDK still goes through sdk"
dev_env_fixture
STUB_OS=Linux STUB_ABSENT="gitleaks java" assert_exit_code 3 dev_env init --install --yes
assert_contains "$LAST_OUT" "manual: install gitleaks from https://github.com/gitleaks/gitleaks/releases" "manual gitleaks"
assert_contains "$LAST_OUT" "INSTALL jdk: sdk env install" "jdk via sdk"
assert_eq "1" "$(stub_log_count '^sdk env install$')" "sdk ran with --yes (no sudo needed)"
assert_contains "$LAST_OUT" "FAIL  gitleaks " "gitleaks still missing"

test_case "Linux (dnf): dnf is used when apt-get is absent"
dev_env_fixture
bin="$(mk_tmp)"
cp "$STUBS_DIR"/* "$bin/"
rm -f "$bin/apt-get"
# The host's own tools without its apt-get: an Ubuntu host (the CI runner) has a real one in /usr/bin.
sys="$(mk_tmp)"
for f in /usr/bin/* /bin/*; do
  n="${f##*/}"
  [ "$n" = apt-get ] || [ -e "$sys/$n" ] || ln -s "$f" "$sys/$n"
done
PATH="$bin:$sys" STUB_OS=Linux STUB_ABSENT="jq gitleaks" assert_exit_code 0 dev_env_in "y
y" init --install
assert_contains "$LAST_OUT" "INSTALL jq: sudo dnf install -y jq" "dnf jq"
assert_contains "$LAST_OUT" "INSTALL gitleaks: sudo dnf install -y gitleaks" "dnf gitleaks"
assert_eq "2" "$(stub_log_count '^sudo dnf install -y')" "two elevated commands after two answers"

test_case "non-interactive --install without --yes installs nothing and prints every command as manual:"
dev_env_fixture
STUB_ABSENT="jq java" assert_exit_code 3 dev_env init --install
assert_contains "$LAST_OUT" "manual: brew install jq" "manual brew"
assert_contains "$LAST_OUT" "manual: sdk env install" "manual sdk"
assert_eq "" "$(stub_log_grep '^(brew|sdk) ')" "nothing ran"

test_case "--install does not apply to check or status (usage error)"
dev_env_fixture
assert_exit_code 2 dev_env check --install
assert_contains "$LAST_OUT" "--install does not apply to check" "message"
assert_exit_code 2 dev_env status --install

finish_tests
