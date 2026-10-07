#!/usr/bin/env bash
# dev-env secrets rule (T067, T081, SC-008): transcripts of init, init --dry-run, init --verbose, status, update,
# reset --yes and down with generated keys contain no value of a *KEY*, *PASSWORD* or *TOKEN* entry of .env and
# nothing gitleaks detects (skipped with a message when gitleaks is not installed locally; CI has it). Secrets are
# written only to platform/compose/.env; the generators pipe into the file writer, never through a command line.
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"
source "$(dirname "$0")/lib/dev_env_fixture.sh"

REAL_GITLEAKS="$(command -v gitleaks || true)"

dev_env_fixture
transcripts="$(mk_tmp)"
record() { # NAME ARGS...: full transcript (stdout and stderr) of one run
  local name="$1"
  shift
  "$P/scripts/dev-env.sh" "$@" </dev/null >"$transcripts/$name.txt" 2>&1 || true
}

test_case "transcripts recorded with generated keys"
record init init
record init-dry init --dry-run
record init-verbose init --verbose
record status status
record update update --verbose
record reset reset --yes
record down down
[ -s "$transcripts/init.txt" ] || _fail "empty init transcript"
id_key="$(env_value IDENTITY_SIGNING_KEY)"
br_key="$(env_value BROWSER_SESSION_KEY)"
[ -n "$id_key" ] && [ -n "$br_key" ] || { _fail "keys were not generated"; finish_tests; exit 1; }

test_case "no secret value of .env appears in any transcript (stdout, stderr, --verbose, --dry-run included)"
secret_values="$(grep -E '^[A-Z0-9_]*(KEY|PASSWORD|TOKEN)[A-Z0-9_]*=.+' "$ENV_FILE" | cut -d= -f2-)"
[ -n "$secret_values" ] || _fail "no secret entries found in .env"
for f in "$transcripts"/*.txt; do
  while IFS= read -r value; do
    [ -n "$value" ] || continue
    assert_not_contains "$(cat "$f")" "$value" "secret value in $(basename "$f")"
  done <<<"$secret_values"
done

test_case "secrets are written only to platform/compose/.env and never passed on a command line"
assert_not_contains "$(cat "$STUB_LOG")" "$id_key" "identity key never in a command line"
assert_not_contains "$(cat "$STUB_LOG")" "$br_key" "browser key never in a command line"
found_elsewhere="$(grep -rl -- "$br_key" "$P" "$H" 2>/dev/null | grep -v "/platform/compose/.env$" || true)"
assert_eq "" "$found_elsewhere" "browser key only in .env"
found_elsewhere="$(grep -rl -- "$id_key" "$P" "$H" 2>/dev/null | grep -v "/platform/compose/.env$" || true)"
assert_eq "" "$found_elsewhere" "identity key only in .env"
assert_eq "" "$(stub_log_grep '^git (add|commit)')" "no git add or commit"
assert_eq "" "$(stub_log_grep '^git config' | grep -v 'core.hooksPath' || true)" "git config only for core.hooksPath"

test_case "--verbose prints commands as '+ <command>' on stderr without secrets"
assert_contains "$(cat "$transcripts/update.txt")" "+ docker compose -p ecommerce-platform --profile core --profile observability up -d --build" "verbose command echo"
assert_not_contains "$(cat "$transcripts/init-verbose.txt")" "$br_key" "verbose output carries no key"

test_case "gitleaks finds nothing in the transcripts"
if [ -z "$REAL_GITLEAKS" ]; then
  echo "SKIP: gitleaks is not installed locally (brew install gitleaks); CI runs this check" >&2
else
  rc=0
  out="$("$REAL_GITLEAKS" detect --no-git --source "$transcripts" --redact --exit-code 1 --no-banner 2>&1)" || rc=$?
  [ "$rc" = 0 ] || _fail "gitleaks reported findings in the transcripts: $(printf '%s' "$out" | tail -n 20)"
fi

finish_tests
