#!/usr/bin/env bash
# dev-env init --start (T064): the project-scoped Compose up with both profiles, health waiting from
# `compose ps --format json`, the three smoke lines and the addresses block exactly as the contract; unhealthy
# component or failed smoke check exits 4; --runner-host adds the ci profile, the registry, two smoke lines and the
# registration pointer, never reads ACCESS_TOKEN; reset --runner-host is a usage error.
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"
source "$(dirname "$0")/lib/dev_env_fixture.sh"

UP_CMD="docker compose -p ecommerce-platform --profile core --profile observability up -d --build"

# ps_json STATE [HEALTH]: every default service in the given state (gateway, grafana and mailpit published).
ps_json() {
  local state="$1" health="${2-}" s port out=""
  for s in gateway identity identity-db catalog catalog-db cart cart-db order order-db payment payment-db \
    notification notification-db kafka mailpit otel-collector loki tempo prometheus grafana; do
    port=0
    case "$s" in gateway) port=8080 ;; grafana) port=3000 ;; mailpit) port=8025 ;; esac
    out="$out{\"Service\":\"$s\",\"State\":\"$state\",\"Health\":\"$health\",\"Publishers\":[{\"PublishedPort\":$port,\"TargetPort\":$port}]}
"
  done
  printf '%s' "$out"
}

test_case "init --start: up with the project name and both profiles, wait, three smoke lines, addresses, exit 0"
dev_env_fixture
assert_exit_code 0 dev_env init --start
out="$LAST_OUT"
assert_eq "1" "$(stub_log_count "^$UP_CMD$")" "one up with -p ecommerce-platform and both profiles"
assert_contains "$out" "PASS  entry      found 200      expected 200 from gateway" "entry smoke line"
assert_contains "$out" "PASS  isolation  found gateway grafana mailpit   expected only gateway published" "isolation smoke line"
assert_contains "$out" "PASS  storefront found 200 text/html, CSP strict   expected GET / serves the app" "storefront smoke line"
assert_contains "$out" "addresses
  storefront  http://localhost:8080/
  api         http://localhost:8080/api/v1/catalog/products
  grafana     http://localhost:3000
  mailpit     http://localhost:8025" "addresses block"
assert_not_contains "$out" "runner host:" "no runner pointer by default"
assert_eq "" "$(stub_log_grep ' (down|rm|stop|kill|prune)( |$)')" "nothing stopped or removed"
every_call_scoped="$(stub_log_grep '^docker compose ' | grep -v -E -- '-p ecommerce-platform|^docker compose version$' || true)"
assert_eq "" "$every_call_scoped" "every compose call names the project"
assert_eq "1" "$(stub_log_count '^curl .*http://localhost:8080/api/v1/catalog/products')" "entry probed on the gateway port"

test_case "addresses follow a recorded GATEWAY_PORT"
dev_env_fixture
cp "$P/platform/compose/.env.example" "$ENV_FILE"
sed -i.bak 's|^GATEWAY_PORT=.*|GATEWAY_PORT=18080|' "$ENV_FILE" && rm -f "$ENV_FILE.bak"
GATEWAY_PORT='' assert_exit_code 0 dev_env init --start
assert_contains "$LAST_OUT" "  storefront  http://localhost:18080/" "storefront address"
assert_contains "$LAST_OUT" "  api         http://localhost:18080/api/v1/catalog/products" "api address"

test_case "a component that never turns healthy: FAIL health line naming it, exit 4"
dev_env_fixture
STUB_COMPOSE_PS_JSON="$(ps_json running healthy | sed 's|"Service":"cart","State":"running","Health":"healthy"|"Service":"cart","State":"running","Health":"starting"|')" \
  assert_exit_code 4 dev_env init --start
assert_contains "$LAST_OUT" "FAIL  health     found not healthy: cart(starting)   expected all components healthy within 1s" "health line"
assert_not_contains "$LAST_OUT" "addresses" "no addresses on failure"
assert_eq "1" "$(stub_log_count "^$UP_CMD$")" "up was attempted once"

test_case "a stopped component and an absent service are reported as stopped"
dev_env_fixture
STUB_COMPOSE_PS_JSON="$(ps_json running healthy | grep -v '"Service":"loki"' | sed 's|"Service":"kafka","State":"running","Health":"healthy"|"Service":"kafka","State":"exited","Health":""|')" \
  assert_exit_code 4 dev_env init --start
assert_contains "$LAST_OUT" "kafka(stopped)" "exited container is stopped"
assert_contains "$LAST_OUT" "loki(stopped)" "absent service is stopped"

test_case "running without a health check counts as healthy; the Compose JSON array format is accepted"
dev_env_fixture
STUB_COMPOSE_PS_JSON="[$(ps_json running '' | sed 's/}$/},/' | tr -d '\n' | sed 's/,$//')]" assert_exit_code 0 dev_env init --start
assert_contains "$LAST_OUT" "PASS  entry " "started"

test_case "failed smoke checks exit 4 with fix lines: gateway not 200, a database published, weak CSP"
dev_env_fixture
STUB_ENTRY_STATUS=503 assert_exit_code 4 dev_env init --start
assert_contains "$LAST_OUT" "FAIL  entry      found 503      expected 200 from gateway" "entry fail"
assert_contains "$LAST_OUT" "      fix (macos): scripts/dev-env.sh status; docker compose -p ecommerce-platform --profile core --profile observability logs gateway" "entry fix"
dev_env_fixture
STUB_COMPOSE_PS_JSON="$(ps_json running healthy | sed 's|"Service":"cart-db","State":"running","Health":"healthy","Publishers":\[{"PublishedPort":0|"Service":"cart-db","State":"running","Health":"healthy","Publishers":[{"PublishedPort":5432|')" \
  assert_exit_code 4 dev_env init --start
assert_contains "$LAST_OUT" "FAIL  isolation  found cart-db gateway grafana mailpit (unexpected: cart-db)   expected only gateway published" "isolation fail"
dev_env_fixture
STUB_STOREFRONT_HEADERS="HTTP/1.1 200 OK
Content-Type: text/html
Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'" assert_exit_code 4 dev_env init --start
assert_contains "$LAST_OUT" "FAIL  storefront found 200 text/html, CSP has unsafe-inline   expected GET / serves the app" "csp fail"
dev_env_fixture
STUB_STOREFRONT_HEADERS="HTTP/1.1 200 OK
Content-Type: application/json" assert_exit_code 4 dev_env init --start
assert_contains "$LAST_OUT" "FAIL  storefront found 200 application/json, CSP missing" "content type and csp missing"

test_case "compose up failure exits 4 with the tail of its output"
dev_env_fixture
STUB_COMPOSE_UP_FAIL=1 assert_exit_code 4 dev_env init --start
assert_contains "$LAST_OUT" "injected failure" "compose output shown"
assert_contains "$LAST_OUT" "FAIL  up " "up line"

test_case "--runner-host: ci profile, registry of platform/ci-runner, broker and ci-reg smoke lines, pointer; ACCESS_TOKEN never read"
dev_env_fixture
printf 'REPO_URL=https://github.com/o/r\nACCESS_TOKEN=ghp_runner_registration_secret_value_000\nRUNNER_WORKDIR=/srv/w\nPACT_BROKER_DB_PASSWORD=x\nPACT_BROKER_BASIC_AUTH_USERNAME=u\nPACT_BROKER_BASIC_AUTH_PASSWORD=p\n' >"$P/platform/ci-runner/.env"
STUB_COMPOSE_PS_JSON="$(ps_json running healthy)
{\"Service\":\"pact-broker-db\",\"State\":\"running\",\"Health\":\"healthy\",\"Publishers\":[]}
{\"Service\":\"pact-broker\",\"State\":\"running\",\"Health\":\"healthy\",\"Publishers\":[{\"PublishedPort\":9292}]}
{\"Service\":\"registry\",\"State\":\"running\",\"Health\":\"healthy\",\"Publishers\":[]}" \
  assert_exit_code 0 dev_env init --start --runner-host
out="$LAST_OUT"
assert_eq "1" "$(stub_log_count '^docker compose -p ecommerce-platform --profile core --profile observability --profile ci up -d --build$')" "ci profile added"
assert_eq "1" "$(stub_log_count "^docker compose -p ci-runner -f $P/platform/ci-runner/docker-compose.yml --env-file $P/platform/ci-runner/.env up -d registry$")" "registry of the ci-runner compose file"
assert_contains "$out" "PASS  isolation  found gateway grafana mailpit pact-broker   expected only gateway published" "broker port allowed"
assert_contains "$out" "PASS  broker     found 200      expected 200 from pact broker" "broker smoke line"
assert_contains "$out" "PASS  ci-reg     found HTTP 401   expected registry answers" "registry smoke line"
assert_contains "$out" "  broker      http://localhost:9292" "broker address"
assert_eq 'runner host: register the runner as described in platform/ci-runner/README.md ("Register the runner")' "$(printf '%s\n' "$out" | tail -n1)" "pointer is the last line"
assert_not_contains "$out" "ghp_runner_registration_secret_value_000" "token never printed"
assert_eq "" "$(stub_log_grep 'ACCESS_TOKEN')" "token never passed to a command"
assert_eq "" "$(stub_log_grep ' config \| register')" "runner never registered"

test_case "--runner-host without the ci-runner .env uses the example file"
dev_env_fixture
STUB_COMPOSE_PS_JSON="$(ps_json running healthy)
{\"Service\":\"pact-broker-db\",\"State\":\"running\",\"Health\":\"healthy\",\"Publishers\":[]}
{\"Service\":\"pact-broker\",\"State\":\"running\",\"Health\":\"healthy\",\"Publishers\":[{\"PublishedPort\":9292}]}
{\"Service\":\"registry\",\"State\":\"running\",\"Health\":\"healthy\",\"Publishers\":[]}" \
  assert_exit_code 0 dev_env init --start --runner-host
assert_eq "1" "$(stub_log_count "--env-file $P/platform/ci-runner/.env.example up -d registry$")" "example env file"

test_case "reset --runner-host is a usage error (exit 2) and starts nothing"
dev_env_fixture
assert_exit_code 2 dev_env reset --runner-host --yes
assert_contains "$LAST_OUT" "ERROR: --runner-host does not apply to reset" "diagnostic"
assert_eq "" "$(stub_log_grep '^docker compose')" "no compose call"

finish_tests
