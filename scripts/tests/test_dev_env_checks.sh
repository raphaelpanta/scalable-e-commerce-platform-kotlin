#!/usr/bin/env bash
# dev-env checks (T061): the 16 checks PASS on a healthy stub set with the exact line format, each FAILs on its
# stubbed failure with the OS fix line, podman and network SKIP as documented, summary line, exit 0 vs 3, speed.
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"
source "$(dirname "$0")/lib/dev_env_fixture.sh"

line_of() { printf '%s\n' "$1" | grep -E "^(PASS|FAIL|SKIP)  $2 " | head -n1; }

test_case "all 16 checks pass on a healthy stub set, exact format, exit 0"
dev_env_fixture
start=$SECONDS
assert_exit_code 0 dev_env check
elapsed=$((SECONDS - start))
out="$LAST_OUT"
[ "$elapsed" -lt 30 ] || _fail "check took ${elapsed}s (limit 30 s)"
assert_eq "$(printf '%-4s  %-10s %-12s   %s' PASS jdk "found 25.0.4" "expected 25")" "$(line_of "$out" jdk)" "jdk line format"
assert_eq "PASS  jq         found 1.7.1    expected installed" "$(line_of "$out" jq)" "jq line"
assert_eq "SKIP  podman     (engine is docker)" "$(line_of "$out" podman)" "podman skipped on docker"
assert_eq "PASS  port       found 8080 free   expected 8080 free" "$(line_of "$out" port)" "port line"
assert_eq "checks: 16 total, 15 PASS, 0 FAIL, 1 SKIP" "$(printf '%s\n' "$out" | tail -n1)" "summary is the last line"
expected_order="jdk engine compose memory cpus disk podman node npm gitleaks curl jq openssl git port network"
actual_order="$(printf '%s\n' "$out" | grep -E '^(PASS|FAIL|SKIP)  ' | awk '{ print $2 }' | tr '\n' ' ' | sed 's/ $//')"
assert_eq "$expected_order" "$actual_order" "check order"
assert_not_contains "$out" "fix (" "no fix line when everything passes"
assert_contains "$(line_of "$out" engine)" "found docker 28.1.1" "engine found value"
assert_contains "$(line_of "$out" compose)" "found v2.29.1" "compose found value"
assert_contains "$(line_of "$out" memory)" "found 12 GiB" "memory found value"
assert_contains "$(line_of "$out" network)" "PASS  network    found HTTP 401" "network reachable"

test_case "check writes nothing and runs no mutating command"
dev_env_fixture
assert_exit_code 0 dev_env check
assert_file_missing "$ENV_FILE" "check must not create .env"
assert_eq "" "$(stub_log_grep ' (up|down|build|pull|install|config core.hooksPath .githooks)( |$)')" "no mutation in the stub log"

test_case "jdk FAILs on a wrong major with the sdk fix line, exit 3"
dev_env_fixture
STUB_JAVA_VERSION=21.0.3 assert_exit_code 3 dev_env check
assert_contains "$LAST_OUT" "FAIL  jdk        found 21.0.3   expected 25" "jdk fail line"
assert_contains "$LAST_OUT" "      fix (macos): sdk env install" "jdk fix (macos)"
assert_contains "$LAST_OUT" "checks: 16 total, 14 PASS, 1 FAIL, 1 SKIP" "summary counts the FAIL"

test_case "jdk PASSes through a Gradle-provisioned toolchain when the default java differs"
dev_env_fixture
mkdir -p "$H/.gradle/jdks/eclipse_adoptium-25-aarch64-os_x.2"
printf 'JAVA_VERSION="25.0.4"\nIMPLEMENTOR="Eclipse Adoptium"\n' >"$H/.gradle/jdks/eclipse_adoptium-25-aarch64-os_x.2/release"
STUB_JAVA_VERSION=27 assert_exit_code 0 dev_env check
assert_contains "$LAST_OUT" "PASS  jdk        found 27 (default), 25.0.4 via Gradle toolchain   expected 25" "jdk via toolchain"

test_case "a missing jdk prints found none"
dev_env_fixture
STUB_ABSENT=java assert_exit_code 3 dev_env check
assert_contains "$LAST_OUT" "FAIL  jdk        found none     expected 25" "found none"

test_case "engine not reachable: FAIL with the engine decision, resources skipped, no engine-specific noise"
dev_env_fixture
STUB_ENGINE=none assert_exit_code 3 dev_env check
assert_contains "$LAST_OUT" "FAIL  engine     found none     expected docker or podman reachable" "engine fail"
assert_contains "$LAST_OUT" "fix (macos): install Docker Desktop, or brew install podman && podman machine init && podman machine start" "engine fix"
assert_contains "$LAST_OUT" "SKIP  memory     (no engine)" "memory skipped without an engine"
assert_not_contains "$LAST_OUT" "brew install --cask" "no cask install anywhere"

test_case "linux fix lines name apt-get and dnf"
dev_env_fixture
STUB_OS=Linux STUB_ABSENT="jq" assert_exit_code 3 dev_env check
assert_contains "$LAST_OUT" "FAIL  jq         found none     expected installed" "jq missing"
assert_contains "$LAST_OUT" "      fix (linux): sudo apt-get install jq" "apt fix"
assert_contains "$LAST_OUT" "      fix (linux): sudo dnf install jq" "dnf fix"
assert_not_contains "$LAST_OUT" "fix (macos)" "no macos fix on linux"

test_case "compose below v2 fails"
dev_env_fixture
STUB_COMPOSE_VERSION=1.29.2 assert_exit_code 3 dev_env check
assert_contains "$LAST_OUT" "FAIL  compose    found v1.29.2   expected v2" "compose fail"
assert_contains "$LAST_OUT" "fix (macos): brew install docker-compose" "compose fix"

test_case "engine resources: memory, cpus and disk fail below the thresholds"
dev_env_fixture
STUB_ENGINE_MEMORY_GIB=8 STUB_ENGINE_CPUS=2 STUB_DF_AVAIL_KB=5000000 assert_exit_code 3 dev_env check
assert_contains "$LAST_OUT" "FAIL  memory     found 8 GiB    expected >= 10 GiB" "memory fail"
assert_contains "$LAST_OUT" "fix (macos): podman machine set --memory 10240" "memory fix"
assert_contains "$LAST_OUT" "FAIL  cpus       found 2        expected >= 4" "cpus fail"
assert_contains "$LAST_OUT" "FAIL  disk       found 4 GiB free (repository)   expected >= 15 GiB free" "disk fail"
assert_contains "$LAST_OUT" "fix (macos): free disk space; podman system prune or docker image prune" "disk fix"
assert_contains "$LAST_OUT" "checks: 16 total, 12 PASS, 3 FAIL, 1 SKIP" "summary"

test_case "podman: FAIL until BUILDAH_FORMAT and ryuk are set, notes are not failures"
dev_env_fixture
STUB_ENGINE=podman STUB_ENGINE_VERSION=5.2.1 assert_exit_code 3 dev_env check
assert_contains "$LAST_OUT" "PASS  engine     found podman 5.2.1   expected docker or podman reachable" "podman engine"
assert_contains "$LAST_OUT" "FAIL  podman     found BUILDAH_FORMAT unset, ryuk enabled   expected BUILDAH_FORMAT=docker, ryuk disabled" "podman fail"
assert_contains "$LAST_OUT" "fix (macos): run: scripts/dev-env.sh init" "podman fix"
assert_contains "$LAST_OUT" "      note: rootless Podman caps concurrent containers" "keyring note"
printf 'ryuk.disabled=true\n' >"$H/.testcontainers.properties"
STUB_ENGINE=podman BUILDAH_FORMAT=docker assert_exit_code 0 dev_env check
assert_contains "$LAST_OUT" "PASS  podman     found BUILDAH_FORMAT=docker, ryuk disabled   expected BUILDAH_FORMAT=docker, ryuk disabled" "podman pass"
assert_contains "$LAST_OUT" "checks: 16 total, 16 PASS, 0 FAIL, 0 SKIP" "all pass on podman"

test_case "podman behind a Docker socket is detected as podman"
dev_env_fixture
STUB_ENGINE=podman-socket assert_exit_code 3 dev_env check
assert_contains "$LAST_OUT" "found podman 28.1.1 (docker socket)" "shim detected"
assert_contains "$LAST_OUT" "note: Podman answers through a Docker-compatible socket" "shim note"
assert_contains "$LAST_OUT" "FAIL  podman " "podman settings checked"

test_case "node, npm, gitleaks, curl, openssl and git failures"
dev_env_fixture
STUB_NODE_VERSION=22.1.0 STUB_NPM_VERSION=10.8.0 STUB_ABSENT="gitleaks curl" STUB_GIT_VERSION=2.8.1 assert_exit_code 3 dev_env check
assert_contains "$LAST_OUT" "FAIL  node       found 22.1.0   expected 24" "node fail"
assert_contains "$LAST_OUT" "fix (macos): brew install node@24" "node fix"
assert_contains "$LAST_OUT" "FAIL  npm        found 10.8.0   expected >= 11" "npm fail"
assert_contains "$LAST_OUT" "FAIL  gitleaks   found none     expected installed" "gitleaks fail"
assert_contains "$LAST_OUT" "fix (macos): brew install gitleaks" "gitleaks fix"
assert_contains "$LAST_OUT" "FAIL  curl       found none     expected installed" "curl fail"
assert_contains "$LAST_OUT" "FAIL  git        found 2.8.1    expected >= 2.9" "git fail"
assert_contains "$LAST_OUT" "fix (macos): brew install git" "git fix"
dev_env_fixture
STUB_OPENSSL_FAIL=1 assert_exit_code 3 dev_env check
assert_contains "$LAST_OUT" "FAIL  openssl    found none     expected ed25519 capable" "openssl fail"
assert_contains "$LAST_OUT" "fix (macos): brew install openssl" "openssl fix"

test_case "port in use by a foreign process fails; the platform's own gateway passes"
dev_env_fixture
STUB_PORT_IN_USE=8080 STUB_COMPOSE_PS_JSON='[]' assert_exit_code 3 dev_env check
assert_contains "$LAST_OUT" "FAIL  port       found 8080 in use   expected 8080 free" "port fail"
assert_contains "$LAST_OUT" "fix (macos): init proposes a free port, or set GATEWAY_PORT in platform/compose/.env" "port fix"
STUB_PORT_IN_USE=8080 assert_exit_code 0 dev_env check
assert_contains "$LAST_OUT" "PASS  port       found 8080 in use (platform gateway)   expected 8080 free" "own gateway"

test_case "GATEWAY_PORT precedence: environment, then .env, then 8080"
dev_env_fixture
GATEWAY_PORT=18080 assert_exit_code 0 dev_env check
assert_contains "$LAST_OUT" "PASS  port       found 18080 free   expected 18080 free" "environment wins"
cp "$P/platform/compose/.env.example" "$ENV_FILE"
printf 'GATEWAY_PORT=9090\n' >>"$ENV_FILE"
assert_exit_code 0 dev_env check
assert_contains "$LAST_OUT" "found 9090 free" ".env value"

test_case "no network is SKIP, never FAIL"
dev_env_fixture
STUB_NETWORK=0 assert_exit_code 0 dev_env check
assert_contains "$LAST_OUT" "SKIP  network    (no network)" "network skip"
assert_contains "$LAST_OUT" "checks: 16 total, 14 PASS, 0 FAIL, 2 SKIP" "summary with two skips"

test_case "COMPOSE_CMD and CONTAINER_ENGINE override detection"
dev_env_fixture
COMPOSE_CMD="docker-compose" assert_exit_code 0 dev_env check
assert_contains "$LAST_OUT" "found v2.29.1 (docker-compose)" "COMPOSE_CMD honoured"
assert_eq "" "$(stub_log_grep '^docker compose version')" "docker compose not probed when COMPOSE_CMD is set"
: >"$STUB_LOG"
STUB_ENGINE=podman-socket CONTAINER_ENGINE=podman assert_exit_code 3 dev_env check
assert_contains "$LAST_OUT" "found podman 28.1.1   expected" "podman CLI chosen"
assert_eq "" "$(stub_log_grep '^docker info')" "docker not probed when CONTAINER_ENGINE=podman"

finish_tests
