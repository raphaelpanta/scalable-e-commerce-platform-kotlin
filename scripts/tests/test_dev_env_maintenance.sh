#!/usr/bin/env bash
# dev-env update, reset, down (T080): update runs up -d --build keeping volumes, waits, smoke-checks, never down;
# reset prints the exact data-loss prompt, only `yes` continues (anything else aborts with exit 0), --yes prints the
# consent line, then down -v scoped to the project, up -d --build, wait, checks; down keeps volumes and prints
# `data kept`, down --volumes needs the confirmation; down on a stopped platform prints `OK platform already down`;
# containers outside the platform are warned about (C3) and never touched; --dry-run prints DRY-RUN: lines only.
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"
source "$(dirname "$0")/lib/dev_env_fixture.sh"

COMPOSE_P="docker compose -p ecommerce-platform --profile core --profile observability"
PROMPT_1="This will DELETE all data of the platform (compose project 'ecommerce-platform'): databases, Kafka, Loki, Tempo, Prometheus, Grafana and Pact Broker volumes. Containers, images and volumes of other projects are not touched."
PROMPT_2="Type 'yes' to continue, anything else aborts [yes/N]: "

no_destructive_engine_call() {
  assert_eq "" "$(stub_log_grep '^(docker|podman) (rm|stop|kill|container|volume|system|image|network) ')" "no engine object addressed by name or filter"
  assert_eq "" "$(stub_log_grep '^docker compose ' | grep -v -E -- '-p |^docker compose version$' || true)" "every compose call carries a project name"
}

test_case "update: up -d --build with the project and profiles, wait, smoke lines, addresses, never down, exit 0"
dev_env_fixture
configured_clone
assert_exit_code 0 dev_env update
assert_eq "1" "$(stub_log_count "^$COMPOSE_P up -d --build$")" "one up"
assert_eq "" "$(stub_log_grep ' down')" "never down"
assert_contains "$LAST_OUT" "PASS  entry " "smoke entry"
assert_contains "$LAST_OUT" "PASS  isolation " "smoke isolation"
assert_contains "$LAST_OUT" "PASS  storefront " "smoke storefront"
assert_contains "$LAST_OUT" "addresses" "addresses"
assert_not_contains "$LAST_OUT" "CHANGE" "no configuration step"
assert_not_contains "$LAST_OUT" "INSTALL" "no install"
no_destructive_engine_call

test_case "update fails with exit 4 when the gateway does not answer"
dev_env_fixture
configured_clone
STUB_ENTRY_STATUS=502 assert_exit_code 4 dev_env update
assert_contains "$LAST_OUT" "FAIL  entry      found 502" "entry fail"

test_case "update without an engine exits 3 after the engine check"
dev_env_fixture
configured_clone
STUB_ENGINE=none assert_exit_code 3 dev_env update
assert_contains "$LAST_OUT" "FAIL  engine " "engine check"
assert_eq "" "$(stub_log_grep ' up ')" "no up"

test_case "reset: exact prompt, 'yes' continues: down -v then up -d --build, wait, checks and smoke; exit 0"
dev_env_fixture
configured_clone
assert_exit_code 0 dev_env_in "yes" reset
out="$LAST_OUT"
assert_contains "$out" "$PROMPT_1" "first prompt line verbatim"
assert_contains "$out" "$PROMPT_2" "second prompt line verbatim"
assert_eq "1" "$(stub_log_count "^$COMPOSE_P down -v$")" "down -v scoped to the project"
assert_eq "1" "$(stub_log_count "^$COMPOSE_P up -d --build$")" "up after down"
down_line="$(grep -n " down -v$" "$STUB_LOG" | cut -d: -f1)"
up_line="$(grep -n " up -d --build$" "$STUB_LOG" | cut -d: -f1)"
[ "$down_line" -lt "$up_line" ] || _fail "down -v must precede up"
assert_contains "$out" "PASS  entry " "smoke after restart"
assert_contains "$out" "checks: 16 total, 15 PASS, 0 FAIL, 1 SKIP" "checks after restart"
assert_not_contains "$out" "confirmed by --yes" "no --yes line when answered interactively"
assert_eq "" "$(stub_log_grep 'prune|--all|-a ')" "no prune, no --all"
no_destructive_engine_call
assert_file_exists "$ENV_FILE" ".env kept"

test_case "reset: any other answer aborts with 'aborted: nothing was changed', exit 0, nothing run"
dev_env_fixture
configured_clone
for answer in "" "y" "Yes" "no" "YES"; do
  : >"$STUB_LOG"
  assert_exit_code 0 dev_env_in "$answer" reset
  assert_contains "$LAST_OUT" "aborted: nothing was changed" "abort line for answer '$answer'"
  assert_eq "" "$(stub_log_grep ' (down|up)( |$)')" "nothing run for answer '$answer'"
done

test_case "reset --yes: consent line instead of the prompt"
dev_env_fixture
configured_clone
assert_exit_code 0 dev_env reset --yes
assert_contains "$LAST_OUT" "confirmed by --yes: deleting platform data" "consent line"
assert_not_contains "$LAST_OUT" "Type 'yes'" "no prompt"
assert_eq "1" "$(stub_log_count " down -v$")" "down -v"

test_case "reset and down --volumes warn about containers outside the platform before the confirmation (C3), never touch them"
dev_env_fixture
configured_clone
STUB_RUNNING_CONTAINERS="ecommerce-platform-gateway-1=ecommerce-platform testcontainers-ryuk-abc= my-postgres=other-project" \
  assert_exit_code 0 dev_env reset --yes
out="$LAST_OUT"
assert_contains "$out" "warning: containers outside the platform are running: testcontainers-ryuk-abc, my-postgres" "warning names the foreign containers"
warn_line="$(printf '%s\n' "$out" | grep -n '^warning: containers outside' | cut -d: -f1)"
confirm_line="$(printf '%s\n' "$out" | grep -n '^confirmed by --yes' | cut -d: -f1)"
[ "$warn_line" -lt "$confirm_line" ] || _fail "warning must precede the confirmation"
assert_eq "" "$(stub_log_grep 'my-postgres|testcontainers-ryuk')" "foreign containers never named in a command"
assert_eq "1" "$(stub_log_count '^docker ps --filter label=com.docker.compose.project=ecommerce-platform --format')" "ownership read through the project label"
: >"$STUB_LOG"
STUB_RUNNING_CONTAINERS="my-postgres=other-project" assert_exit_code 0 dev_env down --volumes --yes
assert_contains "$LAST_OUT" "warning: containers outside the platform are running: my-postgres" "warning on down --volumes"
STUB_RUNNING_CONTAINERS="ecommerce-platform-gateway-1=ecommerce-platform" assert_exit_code 0 dev_env reset --yes
assert_not_contains "$LAST_OUT" "warning: containers outside" "no warning when only platform containers run"

test_case "down keeps the volumes and prints 'data kept'; the ci profile is included so nothing of the platform stays"
dev_env_fixture
configured_clone
assert_exit_code 0 dev_env down
assert_eq "1" "$(stub_log_count "^$COMPOSE_P down$")" "plain down"
assert_eq "" "$(stub_log_grep ' down -v')" "no -v"
assert_contains "$LAST_OUT" "data kept" "data kept"
no_destructive_engine_call

test_case "down --volumes asks the confirmation, 'yes' runs down -v and prints 'data removed'"
dev_env_fixture
configured_clone
assert_exit_code 0 dev_env_in "yes" down --volumes
assert_contains "$LAST_OUT" "$PROMPT_2" "prompt"
assert_eq "1" "$(stub_log_count "^$COMPOSE_P down -v$")" "down -v"
assert_contains "$LAST_OUT" "data removed" "data removed"
: >"$STUB_LOG"
assert_exit_code 0 dev_env_in "no" down --volumes
assert_contains "$LAST_OUT" "aborted: nothing was changed" "abort"
assert_eq "" "$(stub_log_grep ' down')" "nothing run"

test_case "down on a stopped platform prints 'OK platform already down', exit 0, no compose mutation"
dev_env_fixture
configured_clone
STUB_COMPOSE_PS_JSON='[]' assert_exit_code 0 dev_env down
assert_contains "$LAST_OUT" "OK platform already down" "already down"
assert_eq "" "$(stub_log_grep ' down')" "no down"

test_case "--dry-run variants print DRY-RUN: lines only and run no mutating command"
dev_env_fixture
configured_clone
assert_exit_code 0 dev_env update --dry-run
assert_contains "$LAST_OUT" "DRY-RUN: $COMPOSE_P up -d --build" "update dry run"
assert_exit_code 0 dev_env reset --dry-run --yes
assert_contains "$LAST_OUT" "DRY-RUN: ask the data-loss confirmation (compose project 'ecommerce-platform')" "confirmation announced"
assert_contains "$LAST_OUT" "DRY-RUN: $COMPOSE_P down -v" "reset down"
assert_contains "$LAST_OUT" "DRY-RUN: $COMPOSE_P up -d --build" "reset up"
assert_not_contains "$LAST_OUT" "Type 'yes'" "no prompt in a dry run"
assert_exit_code 0 dev_env down --dry-run
assert_contains "$LAST_OUT" "DRY-RUN: $COMPOSE_P down" "down dry run"
assert_not_contains "$LAST_OUT" "data kept" "nothing happened"
assert_exit_code 0 dev_env down --volumes --dry-run
assert_contains "$LAST_OUT" "DRY-RUN: $COMPOSE_P down -v" "down -v dry run"
assert_eq "" "$(stub_log_grep ' (up|down)( |$)')" "no mutating compose call in any dry run"
assert_eq "0" "$(count_lines "$LAST_OUT" 'CHANGE')" "no CHANGE"

test_case "--runner-host on down includes the ci profile and leaves the registry with a note"
dev_env_fixture
configured_clone
assert_exit_code 0 dev_env down --runner-host
assert_eq "1" "$(stub_log_count '^docker compose -p ecommerce-platform --profile core --profile observability --profile ci down$')" "ci profile"
assert_contains "$LAST_OUT" "note: the private registry (compose project 'ci-runner') is left running" "registry note"
assert_eq "" "$(stub_log_grep '^docker compose -p ci-runner')" "ci-runner project untouched"

finish_tests
