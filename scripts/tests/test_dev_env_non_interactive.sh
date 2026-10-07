#!/usr/bin/env bash
# dev-env without a terminal (T066, T081): stdin from /dev/null and DEV_ENV_NON_INTERACTIVE=1 give no prompts, safe
# defaults and a reported line for every skipped choice; reset and down --volumes without --yes refuse with exit 2;
# usage errors exit 2 with usage on stderr; -h prints usage on stdout and exits 0; status, update, reset --yes and
# down behave without a terminal.
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"
source "$(dirname "$0")/lib/dev_env_fixture.sh"

split_streams() { # ARGS...: runs dev-env with separate stdout/stderr captures in OUT and ERR, status in RC
  local o e
  o="$(mk_tmp)/out"
  e="$(mk_tmp)/err"
  RC=0
  "$P/scripts/dev-env.sh" "$@" </dev/null >"$o" 2>"$e" || RC=$?
  OUT="$(cat "$o")"
  ERR="$(cat "$e")"
}

test_case "-h and --help print usage on stdout, nothing on stderr, exit 0"
dev_env_fixture
split_streams -h
assert_eq 0 "$RC" "exit"
assert_contains "$OUT" "Usage: scripts/dev-env.sh [SUBCOMMAND] [FLAGS]" "usage on stdout"
for f in init check status update reset down --install --start --yes --dry-run --runner-host --verbose --volumes; do
  assert_contains "$OUT" "$f" "usage lists $f"
done
assert_eq "" "$ERR" "stderr empty"
split_streams reset --help
assert_eq 0 "$RC" "--help wins over the subcommand"
assert_eq "" "$(stub_log_grep '^docker')" "help touches nothing"

test_case "usage errors: unknown flag, unknown subcommand, two subcommands, flag not applicable -> stderr, exit 2"
dev_env_fixture
split_streams --bogus
assert_eq 2 "$RC" "unknown flag"
assert_contains "$ERR" "ERROR: unknown argument: --bogus" "message on stderr"
assert_contains "$ERR" "Usage: scripts/dev-env.sh" "usage on stderr"
assert_eq "" "$OUT" "nothing on stdout"
split_streams restart
assert_eq 2 "$RC" "unknown subcommand"
split_streams init check
assert_eq 2 "$RC" "two subcommands"
assert_contains "$ERR" "more than one subcommand: init and check" "message"
split_streams check --start
assert_eq 2 "$RC" "--start not applicable to check"
split_streams update --yes
assert_eq 2 "$RC" "--yes not applicable to update"
split_streams init --volumes
assert_eq 2 "$RC" "--volumes not applicable to init"
split_streams down --yes
assert_eq 2 "$RC" "--yes on down needs --volumes"
split_streams status --runner-host --dry-run --verbose
assert_eq 0 "$RC" "applicable flags accepted"
assert_eq "" "$(stub_log_grep ' (up|down)( |$)')" "usage errors run nothing"

test_case "reset without --yes and without a terminal refuses with exit 2 and changes nothing"
dev_env_fixture
split_streams reset
assert_eq 2 "$RC" "exit"
assert_eq "ERROR: reset deletes data; rerun with --yes" "$(printf '%s\n' "$ERR" | grep '^ERROR' | head -n1)" "exact refusal"
assert_eq "" "$(stub_log_grep ' (down|up)( |$)')" "no compose mutation"
DEV_ENV_NON_INTERACTIVE='' split_streams reset
assert_eq 2 "$RC" "stdin from /dev/null alone is non-interactive"

test_case "down --volumes without --yes and without a terminal refuses with exit 2"
dev_env_fixture
split_streams down --volumes
assert_eq 2 "$RC" "exit"
assert_contains "$ERR" "ERROR: down deletes data; rerun with --yes" "refusal"
assert_eq "" "$(stub_log_grep ' down')" "no down"

test_case "init without a terminal: no prompt, ryuk skipped and reported, port proposal applied and reported"
dev_env_fixture
STUB_ENGINE=podman STUB_PORT_IN_USE=8080 STUB_COMPOSE_PS_JSON='[]' split_streams init
assert_eq 3 "$RC" "ryuk still missing -> 3"
assert_not_contains "$OUT" "[y/N]" "no prompt"
assert_contains "$OUT" "SKIP    ryuk       ~/.testcontainers.properties: consent not given (rerun with --yes)" "skipped choice reported"
assert_contains "$OUT" "CHANGE  port       GATEWAY_PORT 8080 in use, recorded 8081 in platform/compose/.env" "free port applied (repository-local)"
assert_file_missing "$H/.testcontainers.properties" "nothing outside the repository"

test_case "init --install without a terminal installs nothing; with --yes only non-sudo commands run"
dev_env_fixture
STUB_OS=Linux STUB_ABSENT="jq java" split_streams init --install
assert_eq 3 "$RC"
assert_contains "$OUT" "manual: sudo apt-get install -y jq" "sudo install is manual"
assert_contains "$OUT" "manual: sdk env install" "non-sudo install is manual without --yes"
assert_eq "" "$(stub_log_grep '^(sudo|sdk) ')" "nothing ran"
STUB_OS=Linux STUB_ABSENT="jq java" split_streams init --install --yes
assert_eq 3 "$RC" "jq still missing"
assert_eq "1" "$(stub_log_count '^sdk env install$')" "non-sudo install ran with --yes"
assert_contains "$OUT" "manual: sudo apt-get install -y jq" "sudo still manual"
assert_eq "" "$(stub_log_grep '^sudo')" "sudo never ran"

test_case "status, update, reset --yes and down work without a terminal"
dev_env_fixture
configured_clone
split_streams status
assert_eq 0 "$RC" "status"
assert_contains "$OUT" "platform (compose project 'ecommerce-platform')" "status table"
split_streams update
assert_eq 0 "$RC" "update"
assert_eq "1" "$(stub_log_count ' up -d --build$')" "update ran up"
split_streams reset --yes
assert_eq 0 "$RC" "reset --yes"
assert_contains "$OUT" "confirmed by --yes: deleting platform data" "consent line"
assert_eq "1" "$(stub_log_count ' down -v$')" "down -v once"
assert_not_contains "$OUT" "Type 'yes'" "no prompt"
split_streams down
assert_eq 0 "$RC" "down"
assert_contains "$OUT" "data kept" "data kept reported"
split_streams down --volumes --yes
assert_eq 0 "$RC" "down --volumes --yes"
assert_contains "$OUT" "data removed" "data removed reported"

finish_tests
