#!/usr/bin/env bash
# Runs the CI runner on demand, without a long-lived credential in the container: while no workflow run of the repository
# is queued or in progress the runner is off; as soon as there is work, the script fetches a one-hour registration token
# with the host's `gh` login (RUNNER_TOKEN) and starts a freshly created, non-ephemeral `runner` container
# (RUNNER_RESTART=no) that serves job after job. Once the runner has been idle (not busy, nothing queued) for
# RUNNER_IDLE_TIMEOUT seconds, the container is stopped and removed, its registration deleted through the API and the
# work directory emptied; then the script waits for the next queued run. Leave ACCESS_TOKEN empty in .env.
# Run it under a process supervisor (systemd service, tmux, nohup). Ctrl-C or SIGTERM stops it once the runner is idle
# (a running job is never cut off; stop the `runner` container to abort one).
#
# Usage: scripts/run-ephemeral.sh [--once] [-h]
#   --once  serve one session of jobs, until the idle timeout, and exit (smoke test of the registration)
# Environment, or the same keys in .env: RUNNER_IDLE_TIMEOUT (seconds, default 900), RUNNER_POLL_INTERVAL (seconds,
#   default 30; how often the API is asked for queued runs and for the runner's busy flag).
# Needs: gh (logged in with a token that may create registration tokens and manage runners of the repository), jq,
#   docker compose.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ONCE=false
for arg in "$@"; do
  case "$arg" in
    --once) ONCE=true ;;
    -h | --help) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

env_value() { { grep -E "^$1=" "$DIR/.env" || true; } | head -n 1 | cut -d= -f2- | sed 's/^"//; s/"$//'; }
[[ -f "$DIR/.env" ]] || { echo "missing $DIR/.env (copy .env.example)" >&2; exit 1; }
command -v gh >/dev/null 2>&1 || { echo "gh is required" >&2; exit 1; }
repo_url="$(env_value REPO_URL)"
repo="${repo_url#https://github.com/}"
repo="${repo%/}"
[[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo "REPO_URL must be https://github.com/<owner>/<repo>" >&2; exit 1; }
[[ -n "$(env_value RUNNER_WORKDIR)" ]] || { echo "RUNNER_WORKDIR is not set in .env" >&2; exit 1; }
idle_timeout="${RUNNER_IDLE_TIMEOUT:-$(env_value RUNNER_IDLE_TIMEOUT)}"
idle_timeout="${idle_timeout:-900}"
poll="${RUNNER_POLL_INTERVAL:-$(env_value RUNNER_POLL_INTERVAL)}"
poll="${poll:-30}"
[[ "$idle_timeout" =~ ^[0-9]+$ && "$poll" =~ ^[1-9][0-9]*$ ]] || { echo "RUNNER_IDLE_TIMEOUT and RUNNER_POLL_INTERVAL must be whole seconds" >&2; exit 1; }
prefix="$(env_value RUNNER_NAME_PREFIX)"
prefix="${prefix:-ecommerce}"

if docker compose version >/dev/null 2>&1; then compose=(docker compose); else compose=(docker-compose); fi
stop=false
trap 'stop=true' INT TERM

log() { echo "$(date -u +%FT%TZ) $*"; }

# pending_runs: number of workflow runs that are queued or in progress, empty when the API does not answer. Any of
# them may have a job waiting for this runner (the repository has no other self-hosted runner).
pending_runs() {
  local queued running
  queued="$(gh api "repos/$repo/actions/runs?status=queued&per_page=1" --jq .total_count 2>/dev/null)" || return 0
  running="$(gh api "repos/$repo/actions/runs?status=in_progress&per_page=1" --jq .total_count 2>/dev/null)" || return 0
  echo $((queued + running))
}

# runner_field <name> <jq field>: field of the registered runner called <name>, empty when unknown.
runner_field() {
  gh api "repos/$repo/actions/runners?per_page=100" --jq ".runners[] | select(.name == \"$1\") | .$2" 2>/dev/null || true
}

# registration_token: one-hour registration token; retries a few times, since a single network error used to end the
# loop under `set -e` and leave the repository without a runner.
registration_token() {
  local attempt token
  for attempt in 1 2 3 4 5; do
    token="$(gh api -X POST "repos/$repo/actions/runners/registration-token" --jq .token 2>/dev/null)" || token=""
    if [[ -n "$token" || "$stop" == "true" ]]; then
      echo "$token"
      return 0
    fi
    log "registration token request failed (attempt $attempt), retrying" >&2
    sleep $((attempt * 10))
  done
}

runner_running() { [[ -n "$("${compose[@]}" ps -q --status running runner 2>/dev/null)" ]]; }

# teardown <name>: stop and remove the container, delete its registration, empty the work directory.
teardown() {
  local name=$1 id
  "${compose[@]}" stop -t 30 runner >/dev/null 2>&1 || true
  "${compose[@]}" rm -f -s runner >/dev/null 2>&1 || true
  # GitHub refuses to delete a runner that still shows online; it goes offline within seconds of the container stopping.
  local attempt
  for attempt in 1 2 3 4 5 6; do
    id="$(runner_field "$name" id)"
    [[ -n "$id" ]] || break
    gh api -X DELETE "repos/$repo/actions/runners/$id" >/dev/null 2>&1 && break
    [[ "$attempt" -lt 6 ]] || log "could not delete the registration of $name" >&2
    sleep 10
  done
  # The work directory is owned by root (the runner runs as root): empty it through a throw-away runner container.
  # shellcheck disable=SC2016  # the variable is expanded by the shell inside the container
  RUNNER_TOKEN="" ACCESS_TOKEN="" RUNNER_RESTART=no "${compose[@]}" run --rm -T --no-deps --entrypoint sh runner \
    -c 'rm -rf "${RUNNER_WORKDIR:?}"/* "${RUNNER_WORKDIR:?}"/.[!.]*' >/dev/null 2>&1 || true
}

cd "$DIR"
log "waiting for queued runs (idle timeout ${idle_timeout}s, poll ${poll}s)"
while [[ "$stop" == "false" ]]; do
  pending="$(pending_runs)"
  if [[ -z "$pending" || "$pending" -eq 0 ]]; then
    sleep "$poll"
    continue
  fi

  token="$(registration_token)"
  if [[ -z "$token" ]]; then
    [[ "$stop" == "true" ]] || sleep 60
    continue
  fi
  name="$prefix-$(date +%s)-$RANDOM"
  log "$pending run(s) pending: starting runner $name"
  if ! RUNNER_TOKEN="$token" ACCESS_TOKEN="" RUNNER_RESTART=no RUNNER_EPHEMERAL="" RUNNER_DISABLE_DEREGISTRATION=true \
    RUNNER_NAME="$name" "${compose[@]}" up -d --force-recreate --no-deps runner >/dev/null 2>&1; then
    log "the runner container did not start" >&2
    teardown "$name"
    sleep 60
    continue
  fi

  # Serve jobs until the runner has been idle for idle_timeout seconds. An unanswered API call counts as activity, so a
  # network problem never stops a runner in the middle of a job.
  last_active=$SECONDS
  while runner_running; do
    busy="$(runner_field "$name" busy)"
    if [[ "$busy" != "false" ]]; then
      last_active=$SECONDS
    elif [[ "$stop" == "true" ]]; then
      break
    else
      pending="$(pending_runs)"
      [[ -n "$pending" && "$pending" -eq 0 ]] || last_active=$SECONDS
      if ((SECONDS - last_active >= idle_timeout)); then
        log "runner $name idle for ${idle_timeout}s: stopping it"
        break
      fi
    fi
    sleep "$poll"
  done
  runner_running || log "runner $name exited on its own" >&2
  teardown "$name"
  log "runner $name removed; waiting for queued runs"
  [[ "$ONCE" == "false" ]] || break
done
