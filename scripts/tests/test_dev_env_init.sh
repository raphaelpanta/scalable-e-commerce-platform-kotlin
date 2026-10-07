#!/usr/bin/env bash
# dev-env init (T062): configuration steps in order with OK/CHANGE/SKIP/DRY-RUN lines; .env created mode 600 from
# the example; secrets generated only when empty and never replaced; GATEWAY_PORT proposal only when the default is
# busy by a foreign process; hooks; Podman settings with consent; .env not git-ignored exits 3; failed writes leave
# .env untouched and no temporary file behind.
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"
source "$(dirname "$0")/lib/dev_env_fixture.sh"

# The step line of step ID (check lines of the same name carry `found` or a parenthesised reason after the name).
step_of() { printf '%s\n' "$1" | grep -E "^(OK|CHANGE|SKIP|DRY-RUN|FAIL) +$2 " | grep -Ev "^(FAIL|PASS|SKIP)  $2 +(found |\()" | head -n1; }
steps_order() { printf '%s\n' "$1" | grep -E '^(OK|CHANGE|SKIP|DRY-RUN) +(env|secret|port|hooks|engine|ryuk) ' | awk '{ print $2 }' | tr '\n' ' ' | sed 's/ $//'; }
file_mode() { stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1"; }
is_base64() { printf '%s' "$1" | grep -Eq '^[A-Za-z0-9+/]+=*$'; }
tmp_files_left() { # names of .env temporary files left in the compose directory (empty when none)
  local f out=""
  for f in "$P/platform/compose"/.env.tmp.*; do [ -e "$f" ] && out="$out $(basename "$f")"; done
  printf '%s' "$out"
}

test_case "fresh clone: checks, then env, secret, port, hooks, engine, ryuk in that order, exit 0"
dev_env_fixture
assert_exit_code 0 dev_env init
out="$LAST_OUT"
assert_eq "env secret port hooks engine ryuk" "$(steps_order "$out")" "step order"
assert_eq "$(printf '%-7s %-10s %s' CHANGE env "created platform/compose/.env from .env.example (mode 600)")" "$(step_of "$out" env)" "env step format"
assert_contains "$(step_of "$out" secret)" "CHANGE  secret     IDENTITY_SIGNING_KEY generated, BROWSER_SESSION_KEY generated" "secret step"
assert_contains "$(step_of "$out" port)" "OK      port       GATEWAY_PORT 8080 free" "port step"
assert_contains "$(step_of "$out" hooks)" "CHANGE  hooks      git config core.hooksPath .githooks" "hooks step"
assert_contains "$(step_of "$out" engine)" "OK      engine     engine is docker, nothing to set" "engine step on docker"
assert_contains "$(step_of "$out" ryuk)" "OK      ryuk       engine is docker, nothing to set" "ryuk step on docker"
assert_contains "$out" "checks: 16 total, 15 PASS, 0 FAIL, 1 SKIP" "check phase summary"
assert_contains "$out" "next: scripts/dev-env.sh init --start" "next step stated"
assert_file_exists "$ENV_FILE" ".env created"
assert_eq "600" "$(file_mode "$ENV_FILE")" ".env mode"
assert_eq ".githooks" "$(cd "$P" && "$REAL_GIT" config core.hooksPath)" "hooks path set in the clone"
id_key="$(env_value IDENTITY_SIGNING_KEY)"
br_key="$(env_value BROWSER_SESSION_KEY)"
[ -n "$id_key" ] || _fail "IDENTITY_SIGNING_KEY empty"
[ -n "$br_key" ] || _fail "BROWSER_SESSION_KEY empty"
is_base64 "$id_key" || _fail "IDENTITY_SIGNING_KEY is not Base64: $id_key"
is_base64 "$br_key" || _fail "BROWSER_SESSION_KEY is not Base64"
assert_eq "44" "${#br_key}" "BROWSER_SESSION_KEY is 32 bytes Base64"
assert_eq "8080" "$(env_value GATEWAY_PORT)" "GATEWAY_PORT keeps the default"
assert_eq "local-internal-token-change-me" "$(env_value INTERNAL_API_TOKEN)" "other example values untouched"
assert_not_contains "$out" "$id_key" "identity key never printed"
assert_not_contains "$out" "$br_key" "browser key never printed"
assert_eq "1" "$(stub_log_count '^openssl genpkey -algorithm ed25519 -outform DER$')" "identity key generated once, by openssl"
assert_eq "1" "$(stub_log_count '^openssl rand -base64 32$')" "browser key generated once, by openssl"
assert_eq "" "$(tmp_files_left)" "no temporary file left"

test_case "existing non-empty secrets are never replaced; only the empty one is generated"
dev_env_fixture
cp "$P/platform/compose/.env.example" "$ENV_FILE"
sed -i.bak 's|^IDENTITY_SIGNING_KEY=.*|IDENTITY_SIGNING_KEY=keep-this-value-as-is|' "$ENV_FILE" && rm -f "$ENV_FILE.bak"
assert_exit_code 0 dev_env init
assert_contains "$(step_of "$LAST_OUT" env)" "OK      env        platform/compose/.env exists" "env already there"
assert_contains "$(step_of "$LAST_OUT" secret)" "CHANGE  secret     IDENTITY_SIGNING_KEY already set, BROWSER_SESSION_KEY generated" "only the empty key"
assert_eq "keep-this-value-as-is" "$(env_value IDENTITY_SIGNING_KEY)" "existing value kept"
[ -n "$(env_value BROWSER_SESSION_KEY)" ] || _fail "BROWSER_SESSION_KEY not generated"
assert_not_contains "$LAST_OUT" "keep-this-value-as-is" "existing secret never printed"
assert_eq "0" "$(stub_log_count '^openssl genpkey -algorithm ed25519 -outform DER$')" "no identity key generated"

test_case "default port busy by a foreign process: the next free port is recorded and used in the port check"
dev_env_fixture
STUB_PORT_IN_USE="8080 8081" STUB_COMPOSE_PS_JSON='[]' assert_exit_code 0 dev_env init
assert_contains "$(step_of "$LAST_OUT" port)" "CHANGE  port       GATEWAY_PORT 8080 in use, recorded 8082 in platform/compose/.env" "port proposal"
assert_eq "8082" "$(env_value GATEWAY_PORT)" "recorded in .env"
assert_contains "$LAST_OUT" "PASS  port       found 8082 free   expected 8082 free" "configuration check uses the new port"
assert_eq "1" "$(grep -c '^GATEWAY_PORT=' "$ENV_FILE")" "GATEWAY_PORT appears once"

test_case "a port the developer chose is never changed; the port check then fails with exit 3"
dev_env_fixture
cp "$P/platform/compose/.env.example" "$ENV_FILE"
sed -i.bak 's|^GATEWAY_PORT=.*|GATEWAY_PORT=18080|' "$ENV_FILE" && rm -f "$ENV_FILE.bak"
STUB_PORT_IN_USE="18080" STUB_COMPOSE_PS_JSON='[]' assert_exit_code 3 dev_env init
assert_contains "$(step_of "$LAST_OUT" port)" "SKIP    port       GATEWAY_PORT 18080 chosen by you is in use (not changed)" "chosen port kept"
assert_eq "18080" "$(env_value GATEWAY_PORT)" ".env unchanged"
assert_contains "$LAST_OUT" "FAIL  port       found 18080 in use   expected 18080 free" "port check fails"
assert_contains "$LAST_OUT" "fix (macos): init proposes a free port, or set GATEWAY_PORT in platform/compose/.env" "fix line"

test_case "the port in use by the platform's own gateway is fine"
dev_env_fixture
STUB_PORT_IN_USE="8080" assert_exit_code 0 dev_env init
assert_contains "$(step_of "$LAST_OUT" port)" "OK      port       GATEWAY_PORT 8080 free" "own gateway counts as free"
assert_eq "8080" "$(env_value GATEWAY_PORT)" "not changed"

test_case "Podman: BUILDAH_FORMAT=docker recorded, ryuk needs consent (--yes), the configuration checks pass"
dev_env_fixture
STUB_ENGINE=podman assert_exit_code 3 dev_env init
assert_contains "$(step_of "$LAST_OUT" engine)" "CHANGE  engine     BUILDAH_FORMAT=docker recorded in platform/compose/.env" "buildah recorded"
assert_contains "$LAST_OUT" "note: export BUILDAH_FORMAT=docker in your shell profile" "shell profile hint"
assert_contains "$(step_of "$LAST_OUT" ryuk)" "SKIP    ryuk       ~/.testcontainers.properties: consent not given (rerun with --yes)" "ryuk needs consent"
assert_file_missing "$H/.testcontainers.properties" "nothing written outside the repository without consent"
assert_contains "$LAST_OUT" "FAIL  podman     found BUILDAH_FORMAT=docker, ryuk enabled" "podman check still fails"
assert_eq "docker" "$(env_value BUILDAH_FORMAT)" "BUILDAH_FORMAT in .env"
STUB_ENGINE=podman assert_exit_code 0 dev_env init --yes
assert_contains "$(step_of "$LAST_OUT" engine)" "OK      engine     BUILDAH_FORMAT=docker already set" "buildah already set"
assert_contains "$(step_of "$LAST_OUT" ryuk)" "CHANGE  ryuk       ryuk.disabled=true set in ~/.testcontainers.properties" "ryuk set with --yes"
assert_eq "ryuk.disabled=true" "$(cat "$H/.testcontainers.properties")" "properties content"
assert_contains "$LAST_OUT" "PASS  podman     found BUILDAH_FORMAT=docker, ryuk disabled" "podman check passes after init"

test_case "Podman: ryuk consent given interactively keeps other properties lines"
dev_env_fixture
printf 'docker.host=unix:///tmp/podman.sock\nryuk.disabled=false\n' >"$H/.testcontainers.properties"
STUB_ENGINE=podman assert_exit_code 0 dev_env_in "y" init
assert_contains "$LAST_OUT" "Set ryuk.disabled=true in ~/.testcontainers.properties (outside the repository)? [y/N]:" "consent prompt"
assert_contains "$(step_of "$LAST_OUT" ryuk)" "CHANGE  ryuk" "ryuk changed"
assert_eq "docker.host=unix:///tmp/podman.sock
ryuk.disabled=true" "$(cat "$H/.testcontainers.properties")" "other lines kept, flag replaced"
STUB_ENGINE=podman assert_exit_code 0 dev_env_in "n" init
assert_contains "$(step_of "$LAST_OUT" ryuk)" "OK      ryuk" "already set on the second run (answer irrelevant)"
assert_not_contains "$LAST_OUT" "[y/N]" "no prompt when nothing needs consent"

test_case "Podman: a BUILDAH_FORMAT the developer set is never overwritten"
dev_env_fixture
cp "$P/platform/compose/.env.example" "$ENV_FILE"
printf 'BUILDAH_FORMAT=oci\n' >>"$ENV_FILE"
STUB_ENGINE=podman assert_exit_code 3 dev_env init --yes
assert_contains "$(step_of "$LAST_OUT" engine)" "SKIP    engine     BUILDAH_FORMAT=oci set by you (not changed)" "kept"
assert_eq "oci" "$(env_value BUILDAH_FORMAT)" "value kept"
assert_contains "$LAST_OUT" "FAIL  podman     found BUILDAH_FORMAT=oci, ryuk disabled" "the configuration check names the value"

test_case ".env not git-ignored: the env step fails, exit 3, nothing written"
dev_env_fixture
: >"$P/.gitignore"
assert_exit_code 3 dev_env init
assert_contains "$LAST_OUT" "FAIL    env        platform/compose/.env is not git-ignored" "env step fails"
assert_contains "$LAST_OUT" "ERROR: platform/compose/.env must be git-ignored" "diagnostic"
assert_file_missing "$ENV_FILE" "no .env written"
assert_not_contains "$LAST_OUT" "CHANGE  secret" "no secret step"

test_case "a failing generator leaves .env untouched and no temporary file (atomic write)"
dev_env_fixture
cp "$P/platform/compose/.env.example" "$ENV_FILE"
before="$(cat "$ENV_FILE")"
STUB_OPENSSL_FAIL=1 assert_exit_code 3 dev_env init
assert_eq "$before" "$(cat "$ENV_FILE")" ".env byte-identical after the failed write"
assert_eq "" "$(tmp_files_left)" "no temporary file left behind"
assert_contains "$LAST_OUT" "FAIL  openssl" "openssl check reports the problem"

test_case "init stops before configuring when a prerequisite is missing (nothing changed, exit 3)"
dev_env_fixture
STUB_ABSENT=jq assert_exit_code 3 dev_env init
assert_file_missing "$ENV_FILE" "no .env"
assert_not_contains "$LAST_OUT" "CHANGE" "no change"
assert_eq "" "$(cd "$P" && "$REAL_GIT" config core.hooksPath || true)" "hooks untouched"
assert_contains "$LAST_OUT" "ERROR: prerequisites missing; nothing was changed" "diagnostic"

test_case "a failing configuration check in the first phase does not stop init (it is repaired)"
dev_env_fixture
STUB_PORT_IN_USE="8080" STUB_COMPOSE_PS_JSON='[]' assert_exit_code 0 dev_env init
assert_contains "$LAST_OUT" "FAIL  port       found 8080 in use" "first phase reports the busy port"
assert_contains "$LAST_OUT" "CHANGE  port " "repaired"
assert_eq "8081" "$(env_value GATEWAY_PORT)" "free port recorded"

finish_tests
