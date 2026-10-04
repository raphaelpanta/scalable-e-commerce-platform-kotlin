#!/usr/bin/env bash
# dev-env status (T079): component table from `compose ps --format json` per data-model.md section 5.4 (healthy,
# starting, unhealthy, stopped; absent -> stopped; running without a health check -> healthy), only the
# ecommerce-platform project, addresses, engine resources line, then the checks and the summary; exit 4 when a core
# component is not healthy while the prerequisites pass, 3 wins over 4; status changes nothing.
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"
source "$(dirname "$0")/lib/dev_env_fixture.sh"

row() { printf '%s\n' "$1" | grep -E "^  $2 +[a-z]+$" | head -n1 | awk '{ print $2 }'; }

test_case "healthy platform: table, addresses, engine line, checks, summary; exit 0"
dev_env_fixture
configured_clone
assert_exit_code 0 dev_env status
out="$LAST_OUT"
assert_eq "platform (compose project 'ecommerce-platform')" "$(printf '%s\n' "$out" | head -n1)" "header first"
assert_eq "healthy" "$(row "$out" gateway)" "gateway row"
assert_eq "healthy" "$(row "$out" grafana)" "observability row listed too"
assert_contains "$out" "addresses
  storefront  http://localhost:8080/" "addresses after the table"
assert_contains "$out" "engine: docker 28.1.1, memory 12 GiB, cpus 6, free disk 60 GiB" "engine resources line"
assert_contains "$out" "PASS  jdk " "checks follow"
assert_eq "checks: 16 total, 15 PASS, 0 FAIL, 1 SKIP" "$(printf '%s\n' "$out" | tail -n1)" "summary last"
table_end="$(printf '%s\n' "$out" | grep -n '^addresses$' | cut -d: -f1)"
checks_start="$(printf '%s\n' "$out" | grep -n '^PASS  jdk' | cut -d: -f1)"
[ "$table_end" -lt "$checks_start" ] || _fail "table and addresses must precede the checks"
assert_eq "" "$(stub_log_grep ' (up|down|build|pull|rm|stop|kill|prune)( |$)')" "status changes nothing"
assert_eq "" "$(stub_log_grep '^docker compose ' | grep -v -E -- '-p ecommerce-platform|^docker compose version$' || true)" "only the platform project is queried"

test_case "states are derived per data-model 5.4: starting, unhealthy, stopped (exited), stopped (absent), running without health check"
dev_env_fixture
configured_clone
STUB_COMPOSE_PS_JSON='{"Service":"gateway","State":"running","Health":"starting","Publishers":[{"PublishedPort":8080}]}
{"Service":"identity","State":"running","Health":"unhealthy","Publishers":[]}
{"Service":"catalog","State":"exited","Health":"","Publishers":[]}
{"Service":"cart","State":"restarting","Health":"","Publishers":[]}
{"Service":"mailpit","State":"running","Health":"","Publishers":[{"PublishedPort":8025}]}
{"Service":"kafka","State":"created","Health":"","Publishers":[]}
{"Service":"grafana","State":"paused","Health":"","Publishers":[{"PublishedPort":3000}]}' assert_exit_code 4 dev_env status
out="$LAST_OUT"
assert_eq "starting" "$(row "$out" gateway)" "health starting"
assert_eq "unhealthy" "$(row "$out" identity)" "unhealthy"
assert_eq "stopped" "$(row "$out" catalog)" "exited -> stopped"
assert_eq "starting" "$(row "$out" cart)" "restarting -> starting"
assert_eq "healthy" "$(row "$out" mailpit)" "running without health check -> healthy"
assert_eq "stopped" "$(row "$out" kafka)" "created -> stopped"
assert_eq "stopped" "$(row "$out" grafana)" "paused -> stopped"
assert_eq "stopped" "$(row "$out" order)" "absent -> stopped"
assert_contains "$out" "checks: 16 total, 15 PASS, 0 FAIL, 1 SKIP" "checks still pass"

test_case "an unhealthy observability component alone does not fail status"
dev_env_fixture
configured_clone
STUB_COMPOSE_PS_JSON="$(docker compose -p ecommerce-platform ps --format json | sed 's|"Service":"loki","State":"running","Health":"healthy"|"Service":"loki","State":"running","Health":"unhealthy"|')" \
  assert_exit_code 0 dev_env status
assert_eq "unhealthy" "$(row "$LAST_OUT" loki)" "loki shown unhealthy"

test_case "3 wins over 4: a failing prerequisite with an unhealthy core component exits 3"
dev_env_fixture
configured_clone
STUB_ABSENT=jq STUB_COMPOSE_PS_JSON='[]' assert_exit_code 3 dev_env status
assert_contains "$LAST_OUT" "FAIL  jq " "prerequisite reported"

test_case "a platform that is down: table says so, exit 0 when the prerequisites pass"
dev_env_fixture
configured_clone
STUB_COMPOSE_PS_JSON='[]' assert_exit_code 0 dev_env status
assert_contains "$LAST_OUT" "  (no containers: the platform is down)" "down notice"

test_case "status without a reachable engine: no table, checks report the engine, exit 3"
dev_env_fixture
configured_clone
STUB_ENGINE=none assert_exit_code 3 dev_env status
assert_contains "$LAST_OUT" "(engine or compose provider not reachable)" "table placeholder"
assert_contains "$LAST_OUT" "engine: not reachable" "resources line"
assert_contains "$LAST_OUT" "FAIL  engine " "engine check fails"

test_case "status --runner-host lists the registry and the broker address"
dev_env_fixture
configured_clone
STUB_COMPOSE_PS_JSON="$(docker compose -p ecommerce-platform ps --format json)
{\"Service\":\"pact-broker\",\"State\":\"running\",\"Health\":\"healthy\",\"Publishers\":[{\"PublishedPort\":9292}]}
{\"Service\":\"registry\",\"State\":\"running\",\"Health\":\"healthy\",\"Publishers\":[]}" assert_exit_code 0 dev_env status --runner-host
assert_eq "healthy" "$(row "$LAST_OUT" registry)" "registry row"
assert_contains "$LAST_OUT" "  broker      http://localhost:9292" "broker address"

finish_tests
