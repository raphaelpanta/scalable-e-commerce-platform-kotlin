#!/usr/bin/env bash
# dev-env idempotency (T063, SC-002, SC-010): a second init prints no CHANGE line, leaves .env byte-identical with
# its modification time unchanged and ~/.testcontainers.properties unchanged; check and status are repeatable; a
# repeated init without --start finishes in under 10 seconds with stubs.
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"
source "$(dirname "$0")/lib/dev_env_fixture.sh"

# GNU first: GNU `stat -f` means --file-system and succeeds with the wrong output.
mtime() { stat -c '%Y' "$1" 2>/dev/null || stat -f '%m' "$1"; }
checksum() { cksum <"$1" | awk '{ print $1 }'; }

test_case "second init on a configured clone: zero CHANGE lines, .env and properties untouched, exit 0, under 10 s"
dev_env_fixture
STUB_ENGINE=podman assert_exit_code 0 dev_env init --yes
assert_eq "5" "$(count_lines "$LAST_OUT" CHANGE)" "first run changes env, secret, hooks, engine, ryuk"
sum="$(checksum "$ENV_FILE")"
t0="$(mtime "$ENV_FILE")"
props_sum="$(checksum "$H/.testcontainers.properties")"
: >"$STUB_LOG"
sleep 1
start=$SECONDS
STUB_ENGINE=podman assert_exit_code 0 dev_env init --yes
elapsed=$((SECONDS - start))
[ "$elapsed" -lt 10 ] || _fail "repeated init took ${elapsed}s (limit 10 s)"
assert_eq "0" "$(count_lines "$LAST_OUT" CHANGE)" "no CHANGE on the second run"
assert_eq "$sum" "$(checksum "$ENV_FILE")" ".env byte-identical"
assert_eq "$t0" "$(mtime "$ENV_FILE")" ".env modification time unchanged"
assert_eq "$props_sum" "$(checksum "$H/.testcontainers.properties")" "properties unchanged"
assert_contains "$LAST_OUT" "OK      env        platform/compose/.env exists" "env OK"
assert_contains "$LAST_OUT" "OK      secret     IDENTITY_SIGNING_KEY already set, BROWSER_SESSION_KEY already set" "secrets OK"
assert_contains "$LAST_OUT" "OK      hooks      git config core.hooksPath .githooks" "hooks OK"
assert_contains "$LAST_OUT" "OK      engine     BUILDAH_FORMAT=docker already set" "engine OK"
assert_contains "$LAST_OUT" "OK      ryuk       ryuk.disabled=true in ~/.testcontainers.properties" "ryuk OK"
assert_eq "0" "$(stub_log_count '^openssl (genpkey -algorithm ed25519 -outform DER|rand)')" "no key generated on the second run"
assert_eq "0" "$(stub_log_count ' config core.hooksPath .githooks$')" "no git config write on the second run"

test_case "check and status are repeatable and change nothing"
dev_env_fixture
configured_clone
sum="$(checksum "$ENV_FILE")"
assert_exit_code 0 dev_env check
first="$LAST_OUT"
assert_exit_code 0 dev_env check
assert_eq "$first" "$LAST_OUT" "check output identical"
assert_exit_code 0 dev_env status
first="$LAST_OUT"
assert_exit_code 0 dev_env status
assert_eq "$first" "$LAST_OUT" "status output identical"
assert_eq "$sum" "$(checksum "$ENV_FILE")" ".env untouched by check and status"
assert_eq "" "$(stub_log_grep ' (up|down|build|pull)( |$)')" "no mutating compose call"

test_case "the exit status of a repeated init matches the first run's final state"
dev_env_fixture
cp "$P/platform/compose/.env.example" "$ENV_FILE"
sed -i.bak 's|^GATEWAY_PORT=.*|GATEWAY_PORT=18080|' "$ENV_FILE" && rm -f "$ENV_FILE.bak"
STUB_PORT_IN_USE=18080 STUB_COMPOSE_PS_JSON='[]' assert_exit_code 3 dev_env init
STUB_PORT_IN_USE=18080 STUB_COMPOSE_PS_JSON='[]' assert_exit_code 3 dev_env init
assert_eq "0" "$(count_lines "$LAST_OUT" CHANGE)" "no CHANGE on the repeat"

finish_tests
