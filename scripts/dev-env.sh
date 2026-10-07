#!/usr/bin/env bash
# One-command local development bootstrap (feature 005, FR-020 to FR-030 and FR-033). The exact behaviour, output
# formats, exit codes and prompts are specified in specs/005-storefront-dev-bootstrap/contracts/dev-env-cli.md and
# summarised in docs/dev-environment.md.
#
# Usage: scripts/dev-env.sh [SUBCOMMAND] [FLAGS]
#   init (default)  check the prerequisites, configure the clone (.env, secrets, free port, hooks, Podman
#                   settings), with --start build and start the platform, wait for health, smoke-check it
#   check           the checks only; exit 0, or 3 when one fails
#   status          component health, addresses, engine resources, then the checks
#   update          rebuild and restart only changed components (Compose build cache), keep data, check
#   reset           confirm, delete the platform data (down -v of the platform project only), rebuild, check
#   down            stop the platform and keep its data; --volumes deletes the data after confirmation
# Flags:
#   --install       init: install missing tools with the package manager (announced; sudo only after a prompt)
#   --start         init: start the platform after configuring
#   --yes           non-interactive consent (outside-repository settings, installs, data loss); never answers sudo
#   --dry-run       print every mutation as `DRY-RUN: ...` and perform none; checks still run
#   --runner-host   CI host mode: adds the ci profile (Pact Broker) and the private registry; never registers
#   --verbose       print each command run as `+ <command>` on stderr
#   --volumes       down: also delete the data volumes (confirmation required)
#   -h, --help      this text
# Exit codes: 0 ok, 2 usage, 3 prerequisites missing, 4 platform failed to start or failed its checks.
# Environment: GATEWAY_PORT, COMPOSE_CMD, CONTAINER_ENGINE, DEV_ENV_NON_INTERACTIVE, NO_COLOR (see the contract).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# The Compose library first (COMPOSE_DIR, http_status, ...), then common.sh so that its `log` and `run` win.
# shellcheck source=../platform/compose/scripts/lib.sh
source "$REPO_ROOT/platform/compose/scripts/lib.sh"
# shellcheck source=lib/common.sh
source "$REPO_ROOT/scripts/lib/common.sh"
# shellcheck source=lib/dev-env/output.sh
source "$REPO_ROOT/scripts/lib/dev-env/output.sh"
# shellcheck source=lib/dev-env/engine.sh
source "$REPO_ROOT/scripts/lib/dev-env/engine.sh"
# shellcheck source=lib/dev-env/config.sh
source "$REPO_ROOT/scripts/lib/dev-env/config.sh"
# shellcheck source=lib/dev-env/checks.sh
source "$REPO_ROOT/scripts/lib/dev-env/checks.sh"
# shellcheck source=lib/dev-env/installs.sh
source "$REPO_ROOT/scripts/lib/dev-env/installs.sh"
# shellcheck source=lib/dev-env/platform.sh
source "$REPO_ROOT/scripts/lib/dev-env/platform.sh"

# --verbose prints the commands on stderr (common.sh's vlog writes to stdout; the contract wants stderr).
vlog() {
  [ "$VERBOSE" = 1 ] || return 0
  printf '%s\n' "$*" >&2
}

usage() {
  sed -n '/^# Usage:/,/^# Environment:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

SUBCOMMAND=""
INSTALL=0
START=0
YES=0
RUNNER_HOST=0
VOLUMES=0
GATEWAY_PORT_EFFECTIVE=""

for arg in "$@"; do
  case "$arg" in -h | --help) usage; exit 0 ;; esac
done

for arg in "$@"; do
  case "$arg" in
    init | check | status | update | reset | down)
      [ -z "$SUBCOMMAND" ] || usage_error "more than one subcommand: $SUBCOMMAND and $arg"
      SUBCOMMAND="$arg" ;;
    --install) INSTALL=1 ;;
    --start) START=1 ;;
    --yes) YES=1 ;;
    --dry-run) DRY_RUN=1 ;;
    --runner-host) RUNNER_HOST=1 ;;
    --verbose) VERBOSE=1 ;;
    --volumes) VOLUMES=1 ;;
    *) usage_error "unknown argument: $arg" ;;
  esac
done
SUBCOMMAND="${SUBCOMMAND:-init}"

# Flag applicability (contract table): a flag the subcommand does not take is a usage error.
not_applicable() { usage_error "$1 does not apply to $SUBCOMMAND"; }
case "$SUBCOMMAND" in
  init) [ "$VOLUMES" = 0 ] || not_applicable --volumes ;;
  check | status)
    [ "$INSTALL" = 0 ] || not_applicable --install
    [ "$START" = 0 ] || not_applicable --start
    [ "$YES" = 0 ] || not_applicable --yes
    [ "$VOLUMES" = 0 ] || not_applicable --volumes ;;
  update)
    [ "$INSTALL" = 0 ] || not_applicable --install
    [ "$START" = 0 ] || not_applicable --start
    [ "$YES" = 0 ] || not_applicable --yes
    [ "$VOLUMES" = 0 ] || not_applicable --volumes ;;
  reset)
    [ "$INSTALL" = 0 ] || not_applicable --install
    [ "$START" = 0 ] || not_applicable --start
    [ "$RUNNER_HOST" = 0 ] || not_applicable --runner-host
    [ "$VOLUMES" = 0 ] || not_applicable --volumes ;;
  down)
    [ "$INSTALL" = 0 ] || not_applicable --install
    [ "$START" = 0 ] || not_applicable --start
    [ "$YES" = 0 ] || [ "$VOLUMES" = 1 ] || not_applicable "--yes (without --volumes)" ;;
esac
export DRY_RUN VERBOSE
[ "$RUNNER_HOST" = 0 ] || enable_runner_host
trap cleanup_tmp_files EXIT

# require_platform_tools: engine and Compose provider, printed as check lines; exit 3 when missing.
require_platform_tools() {
  reset_check_counts
  check_engine
  check_compose
  if [ "$CHECK_FAIL" != 0 ]; then
    summary_line
    exit 3
  fi
  GATEWAY_PORT_EFFECTIVE="$(effective_gateway_port)"
  [ "$ENGINE" != podman ] || export BUILDAH_FORMAT="${BUILDAH_FORMAT:-docker}"
}

cmd_check() {
  run_checks all
  summary_line
  [ "$CHECK_FAIL" = 0 ] || exit 3
}

cmd_init() {
  run_checks all
  summary_line
  if prerequisite_failed; then
    if [ "$INSTALL" = 1 ]; then
      local failed
      failed="$(failed_prerequisites)"
      # shellcheck disable=SC2086  # a space-separated list of check names
      run_installs $failed
      printf 're-checking: %s\n' "$failed"
      reset_check_counts
      run_checks "$failed"
      summary_line
      if prerequisite_failed; then
        error "prerequisites still missing; nothing was configured"
        exit 3
      fi
    else
      error "prerequisites missing; nothing was changed (rerun with --install to install them, or follow the fix lines)"
      exit 3
    fi
  fi
  configure_clone
  reset_check_counts
  run_checks "podman port"
  if [ "$CHECK_FAIL" != 0 ]; then
    error "configuration checks failed; the platform was not started"
    exit 3
  fi
  GATEWAY_PORT_EFFECTIVE="$(effective_gateway_port)"
  if [ "$START" = 1 ]; then
    start_platform || exit 4
  else
    printf 'next: scripts/dev-env.sh init --start (starts the platform and runs the smoke checks)\n'
  fi
}

cmd_status() {
  detect_engine || true
  detect_compose_cmd || true
  GATEWAY_PORT_EFFECTIVE="$(effective_gateway_port)"
  local core_ok=0
  if [ -n "$ENGINE_BIN" ] && [ "${#COMPOSE[@]}" -gt 0 ]; then
    print_status_table || core_ok=1
  else
    printf "platform (compose project '%s')\n  (engine or compose provider not reachable)\n" "$PROJECT"
  fi
  print_addresses
  print_engine_resources
  reset_check_counts
  run_checks all
  summary_line
  [ "$CHECK_FAIL" = 0 ] || exit 3
  [ "$core_ok" = 0 ] || exit 4
}

cmd_update() {
  require_platform_tools
  reset_check_counts
  start_platform || exit 4
}

cmd_reset() {
  require_platform_tools
  warn_foreign_containers
  confirm_data_loss
  pc_mutate down -v
  reset_check_counts
  local start_rc=0
  start_platform || start_rc=4
  if [ "$DRY_RUN" = 0 ]; then
    reset_check_counts
    run_checks all
    summary_line
    [ "$CHECK_FAIL" = 0 ] || exit 3
  fi
  exit "$start_rc"
}

cmd_down() {
  require_platform_tools
  if [ "$VOLUMES" = 1 ]; then
    warn_foreign_containers
    confirm_data_loss
    pc_mutate down -v
    [ "$DRY_RUN" = 1 ] || printf 'data removed\n'
  else
    if ! platform_has_containers; then
      printf 'OK platform already down\n'
    else
      pc_mutate down
      [ "$DRY_RUN" = 1 ] || printf 'data kept\n'
    fi
  fi
  [ "$RUNNER_HOST" = 0 ] ||
    printf "note: the private registry (compose project '%s') is left running; see platform/ci-runner/README.md (\"Operations\")\\n" "$CI_RUNNER_PROJECT"
}

detect_os
case "$SUBCOMMAND" in
  init) cmd_init ;;
  check) cmd_check ;;
  status) cmd_status ;;
  update) cmd_update ;;
  reset) cmd_reset ;;
  down) cmd_down ;;
esac
