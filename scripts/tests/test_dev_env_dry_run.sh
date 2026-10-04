#!/usr/bin/env bash
# dev-env --dry-run (T063): one `DRY-RUN:` line per would-be mutation, nothing written, no git config write, no
# install, no mutating Compose command; secrets announced without a value; a later real run still finds everything
# to do; the exit status follows the read-only parts (3 on a missing prerequisite, never 4).
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"
source "$(dirname "$0")/lib/dev_env_fixture.sh"

test_case "init --dry-run on a fresh clone: DRY-RUN step lines plus one DRY-RUN: line per mutation, nothing changed"
dev_env_fixture
assert_exit_code 0 dev_env init --dry-run --start
out="$LAST_OUT"
assert_file_missing "$ENV_FILE" ".env not created"
assert_eq "" "$(cd "$P" && "$REAL_GIT" config core.hooksPath || true)" "hooks path not written"
assert_eq "0" "$(count_lines "$out" CHANGE)" "no CHANGE line"
assert_contains "$out" "DRY-RUN env        create platform/compose/.env from .env.example (mode 600)" "env step"
assert_contains "$out" "DRY-RUN: create platform/compose/.env from .env.example (mode 600)" "env mutation"
assert_contains "$out" "DRY-RUN secret     IDENTITY_SIGNING_KEY would be generated, BROWSER_SESSION_KEY would be generated" "secret step"
assert_contains "$out" "DRY-RUN: generate IDENTITY_SIGNING_KEY in platform/compose/.env" "identity key announced"
assert_contains "$out" "DRY-RUN: generate BROWSER_SESSION_KEY in platform/compose/.env" "browser key announced"
assert_contains "$out" "DRY-RUN hooks      git config core.hooksPath .githooks" "hooks step"
assert_contains "$out" "DRY-RUN: git config core.hooksPath .githooks" "hooks mutation"
assert_contains "$out" "DRY-RUN: docker compose -p ecommerce-platform --profile core --profile observability up -d --build" "start mutation"
assert_eq "5" "$(count_lines "$out" 'DRY-RUN:')" "exactly one DRY-RUN: line per mutation (env, 2 keys, hooks, up)"
assert_eq "0" "$(stub_log_count '^openssl (genpkey -algorithm ed25519 -outform DER|rand)')" "no key generated"
assert_eq "0" "$(stub_log_count ' config core.hooksPath .githooks$')" "no git config write"
assert_eq "" "$(stub_log_grep ' (up|down|build|pull)( |$)')" "no mutating compose call"
assert_eq "" "$(stub_log_grep '^(brew|apt-get|dnf|sudo|sdk) ')" "no installer"
assert_contains "$out" "checks: 16 total, 15 PASS, 0 FAIL, 1 SKIP" "checks still run"
assert_not_contains "$out" "addresses" "nothing started, no addresses"

test_case "a real run after the dry run still finds everything to do"
assert_exit_code 0 dev_env init
assert_eq "3" "$(count_lines "$LAST_OUT" CHANGE)" "env, secret and hooks change"
assert_file_exists "$ENV_FILE"

test_case "dry run on a configured clone prints no DRY-RUN: line and is identical on repeat"
dev_env_fixture
configured_clone
assert_exit_code 0 dev_env init --dry-run
first="$LAST_OUT"
assert_eq "0" "$(count_lines "$first" 'DRY-RUN:')" "nothing to do"
assert_exit_code 0 dev_env init --dry-run
assert_eq "$first" "$LAST_OUT" "deterministic"

test_case "dry run with Podman announces the engine and ryuk settings without touching the files"
dev_env_fixture
STUB_ENGINE=podman assert_exit_code 3 dev_env init --dry-run --yes
assert_contains "$LAST_OUT" "DRY-RUN engine     record BUILDAH_FORMAT=docker in platform/compose/.env" "engine step"
assert_contains "$LAST_OUT" "DRY-RUN: set BUILDAH_FORMAT=docker in platform/compose/.env" "engine mutation"
assert_contains "$LAST_OUT" "DRY-RUN ryuk       set ryuk.disabled=true in ~/.testcontainers.properties" "ryuk step"
assert_contains "$LAST_OUT" "DRY-RUN: set ryuk.disabled=true in ~/.testcontainers.properties" "ryuk mutation"
assert_file_missing "$H/.testcontainers.properties" "properties not written"
assert_file_missing "$ENV_FILE" ".env not written"
assert_contains "$LAST_OUT" "FAIL  podman " "configuration check reports what the real run would fix"

test_case "dry run with --install prints the installs as DRY-RUN: lines and runs none"
dev_env_fixture
STUB_ABSENT="jq gitleaks" assert_exit_code 3 dev_env init --dry-run --install
assert_contains "$LAST_OUT" "INSTALL jq: brew install jq" "announced"
assert_contains "$LAST_OUT" "DRY-RUN: brew install jq" "dry-run install"
assert_contains "$LAST_OUT" "DRY-RUN: brew install gitleaks" "dry-run install"
assert_eq "" "$(stub_log_grep '^brew ')" "brew not run"
assert_contains "$LAST_OUT" "re-checking: gitleaks jq" "re-check happens, in check order"
assert_file_missing "$ENV_FILE" "nothing configured while prerequisites are missing"

test_case "dry run exits 3 on a missing prerequisite and never 4"
dev_env_fixture
STUB_ABSENT=curl assert_exit_code 3 dev_env init --dry-run --start
assert_eq "0" "$(count_lines "$LAST_OUT" 'DRY-RUN:')" "no mutation planned when prerequisites fail"
dev_env_fixture
STUB_COMPOSE_PS_JSON='[]' assert_exit_code 0 dev_env init --dry-run --start
assert_contains "$LAST_OUT" "DRY-RUN: docker compose -p ecommerce-platform --profile core --profile observability up -d --build" "start planned"

test_case "a busy default port is proposed, not recorded, in a dry run"
dev_env_fixture
STUB_PORT_IN_USE=8080 STUB_COMPOSE_PS_JSON='[]' assert_exit_code 3 dev_env init --dry-run
assert_contains "$LAST_OUT" "DRY-RUN port       GATEWAY_PORT 8080 in use, would record 8081 in platform/compose/.env" "port step"
assert_contains "$LAST_OUT" "DRY-RUN: set GATEWAY_PORT=8081 in platform/compose/.env" "port mutation"
assert_file_missing "$ENV_FILE" "not written"

finish_tests
