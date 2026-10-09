#!/usr/bin/env bash
# Runs the CI runners on demand, without a long-lived credential in the containers: while no workflow run of the
# repository is queued or in progress no runner is up; as soon as there is work, the script fetches a one-hour
# registration token with the host's `gh` login (RUNNER_TOKEN) and starts freshly created, non-ephemeral runner
# containers (RUNNER_RESTART=no) that serve job after job: `runner` for every job and, unless RUNNER_LIGHT=false, the
# small `runner-light` for the jobs labelled `ecommerce-light` (image builds, publish, aggregates), so those run next to a
# Gradle job instead of queueing behind it. Once a runner has been idle (not busy, nothing queued) for
# RUNNER_IDLE_TIMEOUT seconds, its container is stopped and removed, its registration deleted through the API and its
# work directory emptied; it starts again with the next queued run. Leave ACCESS_TOKEN empty in .env.
# Run it under a process supervisor (systemd service, launchd, tmux, nohup). Ctrl-C or SIGTERM stops it once the
# runners are idle (a running job is never cut off; stop the runner container to abort one).
#
# Portable: the script runs on a Linux host or on macOS (bash 3.2, BSD tools) next to a VM engine (Podman machine,
# Docker Desktop, Colima). Everything it sizes or mounts is taken from the ENGINE (`docker info`, engine volumes), never
# from the machine the script runs on, because the runners and every container their jobs start live there.
#
# Usage: scripts/run-ephemeral.sh [--once] [--print-config] [-h]
#   --once          serve one session of jobs, until the runners are idle, and exit (smoke test of the registration)
#   --print-config  print the resolved sizing and work directories and exit (no GitHub call, no container started)
# Environment, or the same keys in .env:
#   RUNNER_IDLE_TIMEOUT    seconds a runner may stay idle before it is stopped (default 900)
#   RUNNER_POLL_INTERVAL   seconds between two looks at the queued runs and the runners' busy flags (default 30)
#   RUNNER_LIGHT           false: no light runner, `runner` serves every job (default true)
#   RUNNER_WORKDIR         work directory of `runner` on the engine host; empty (default): the engine volume
#                          ci-runner-work, mounted at its own mount point (native disk of the engine host)
#   RUNNER_LIGHT_WORKDIR   the same for `runner-light` (default: the engine volume ci-runner-work-light)
#   RUNNER_CPUS, RUNNER_MEM_LIMIT, RUNNER_GRADLE_OPTS   caps of `runner` and the Gradle settings of its jobs (default:
#                          all engine CPUs but one, the engine memory less 4 GiB, workers and heap sized to them)
#   RUNNER_LIGHT_CPUS, RUNNER_LIGHT_MEM_LIMIT           caps of `runner-light` (default 2 CPUs, 1536m)
#   RUNNER_PRUNE           false: keep dangling images and build cache older than 72 h (default: pruned between sessions)
# Needs: gh (logged in with a token that may create registration tokens and manage runners of the repository),
#   docker (or podman's docker CLI) with compose.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ONCE=false
PRINT=false
for arg in "$@"; do
  case "$arg" in
    --once) ONCE=true ;;
    --print-config) PRINT=true ;;
    -h | --help) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

env_value() { { grep -E "^$1=" "$DIR/.env" || true; } | head -n 1 | cut -d= -f2- | sed 's/^"//; s/"$//'; }
# setting <name> <default>: the environment wins over .env, .env over the default.
setting() {
  local value
  value="$(printenv "$1" || true)"
  [[ -n "$value" ]] || value="$(env_value "$1")"
  echo "${value:-$2}"
}

[[ -f "$DIR/.env" ]] || { echo "missing $DIR/.env (copy .env.example)" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; exit 1; }
repo_url="$(env_value REPO_URL)"
repo="${repo_url#https://github.com/}"
repo="${repo%/}"
[[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo "REPO_URL must be https://github.com/<owner>/<repo>" >&2; exit 1; }
idle_timeout="$(setting RUNNER_IDLE_TIMEOUT 900)"
poll="$(setting RUNNER_POLL_INTERVAL 30)"
[[ "$idle_timeout" =~ ^[0-9]+$ && "$poll" =~ ^[1-9][0-9]*$ ]] || { echo "RUNNER_IDLE_TIMEOUT and RUNNER_POLL_INTERVAL must be whole seconds" >&2; exit 1; }
prefix="$(setting RUNNER_NAME_PREFIX ecommerce)"
light="$(setting RUNNER_LIGHT true)"
prune="$(setting RUNNER_PRUNE true)"

if docker compose version >/dev/null 2>&1; then compose=(docker compose); else compose=(docker-compose); fi
compose+=(--profile light)
stop=false
trap 'stop=true' INT TERM

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*"; }

# --- Sizing, from the engine ------------------------------------------------------------------------------------------
# Docker's template names first, then Podman's (its docker-compatible CLI answers `info` with its own report).
engine="$(docker info --format '{{.NCPU}} {{.MemTotal}}' 2>/dev/null)" ||
  engine="$(docker info --format '{{.Host.CPUs}} {{.Host.MemTotal}}' 2>/dev/null)" ||
  { echo "the container engine does not answer (docker info)" >&2; exit 1; }
engine_cpus="${engine%% *}"
engine_mem_gib=$(( (${engine##* } + 536870912) / 1073741824 ))   # rounded: a 14 GiB VM reports 13.6
[[ "$engine_cpus" -ge 1 && "$engine_mem_gib" -ge 1 ]] || { echo "unexpected docker info: $engine" >&2; exit 1; }

default_cpus=$(( engine_cpus > 2 ? engine_cpus - 1 : engine_cpus ))
default_mem_gib=$(( engine_mem_gib > 8 ? engine_mem_gib - 4 : engine_mem_gib / 2 ))
[[ "$default_mem_gib" -ge 2 ]] || default_mem_gib=2
RUNNER_CPUS="$(setting RUNNER_CPUS "$default_cpus")"
RUNNER_MEM_LIMIT="$(setting RUNNER_MEM_LIMIT "${default_mem_gib}g")"
# Gradle: one worker per CPU left after the daemons, at most 6 (more parallel Testcontainers modules overload the engine
# without making the build faster); the daemon heap grows with the cap, which also has to hold the Kotlin daemon (2 GB)
# and the test JVMs.
mem_cap_gib="${RUNNER_MEM_LIMIT%[gG]}"
[[ "$mem_cap_gib" =~ ^[0-9]+$ ]] || mem_cap_gib="$default_mem_gib"
workers=$(( RUNNER_CPUS > 3 ? RUNNER_CPUS - 2 : 1 ))
[[ "$workers" -le 6 ]] || workers=6
heap=$(( mem_cap_gib >= 10 ? 4 : (mem_cap_gib >= 7 ? 3 : 2) ))
RUNNER_GRADLE_OPTS="$(setting RUNNER_GRADLE_OPTS "-Dorg.gradle.workers.max=$workers \"-Dorg.gradle.jvmargs=-Xmx${heap}g -XX:+UseParallelGC -Dfile.encoding=UTF-8 --sun-misc-unsafe-memory-access=allow\"")"
light_cpus_default=$(( engine_cpus >= 2 ? 2 : 1 ))
RUNNER_LIGHT_CPUS="$(setting RUNNER_LIGHT_CPUS "$light_cpus_default")"
RUNNER_LIGHT_MEM_LIMIT="$(setting RUNNER_LIGHT_MEM_LIMIT 1536m)"
export RUNNER_CPUS RUNNER_MEM_LIMIT RUNNER_GRADLE_OPTS RUNNER_LIGHT_CPUS RUNNER_LIGHT_MEM_LIMIT

# --- Work directories -------------------------------------------------------------------------------------------------
# workdir <configured> <volume>: the configured directory of the engine host, or the mount point of an engine volume
# (created on first use). A volume lives on the engine's own disk, which is what a macOS engine needs: its VM reaches
# the Mac's files (/Users) through a file share that is 30 to 50 times slower for small files.
workdir() {
  local dir
  if [[ -n "$1" ]]; then
    echo "$1"
    return 0
  fi
  docker volume inspect "$2" >/dev/null 2>&1 || docker volume create "$2" >/dev/null
  dir="$(docker volume inspect --format '{{.Mountpoint}}' "$2")"
  [[ "$dir" == /* ]] || { echo "volume $2 has no mount point on the engine host" >&2; return 1; }
  echo "$dir"
}
RUNNER_WORKDIR="$(workdir "$(setting RUNNER_WORKDIR "")" ci-runner-work)"
RUNNER_LIGHT_WORKDIR="$(workdir "$(setting RUNNER_LIGHT_WORKDIR "")" ci-runner-work-light)"
export RUNNER_WORKDIR RUNNER_LIGHT_WORKDIR

services=(runner)
[[ "$light" == "false" ]] || services+=(runner-light)

if [[ "$PRINT" == "true" ]]; then
  echo "engine: $engine_cpus CPUs, ${engine_mem_gib} GiB"
  echo "runner: cpus=$RUNNER_CPUS mem=$RUNNER_MEM_LIMIT workdir=$RUNNER_WORKDIR"
  echo "runner GRADLE_OPTS: $RUNNER_GRADLE_OPTS"
  if [[ "$light" == "false" ]]; then
    echo "runner-light: off"
  else
    echo "runner-light: cpus=$RUNNER_LIGHT_CPUS mem=$RUNNER_LIGHT_MEM_LIMIT workdir=$RUNNER_LIGHT_WORKDIR"
  fi
  exit 0
fi

command -v gh >/dev/null 2>&1 || { echo "gh is required" >&2; exit 1; }

# --- GitHub API -------------------------------------------------------------------------------------------------------
# pending_runs: number of workflow runs that are queued or in progress, empty when the API does not answer. Any of
# them may have a job waiting for these runners (the repository has no other self-hosted runner).
pending_runs() {
  local queued running
  queued="$(gh api "repos/$repo/actions/runs?status=queued&per_page=1" --jq .total_count 2>/dev/null)" || return 0
  running="$(gh api "repos/$repo/actions/runs?status=in_progress&per_page=1" --jq .total_count 2>/dev/null)" || return 0
  echo $((queued + running))
}

# registered_runners: "<name> <busy> <status> <id>" per registered runner of the repository; empty when the API does
# not answer (the caller then treats every runner as busy).
registered_runners() {
  gh api "repos/$repo/actions/runners?per_page=100" \
    --jq '.runners[] | "\(.name) \(.busy) \(.status) \(.id)"' 2>/dev/null || true
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

# delete_registration <name>: GitHub refuses to delete a runner that still shows online; it goes offline within a
# minute of its container stopping. Leftovers are removed by delete_offline_runners at the next start.
delete_registration() {
  local name=$1 id attempt
  for attempt in 1 2 3 4 5 6 7 8 9; do
    id="$(registered_runners | awk -v n="$name" '$1 == n { print $4 }')"
    [[ -n "$id" ]] || return 0
    gh api -X DELETE "repos/$repo/actions/runners/$id" >/dev/null 2>&1 && return 0
    sleep 10
  done
  log "could not delete the registration of $name yet (removed at the next start)" >&2
}

# delete_offline_runners: registrations of this script (name prefix) that a crash or a slow API left behind.
delete_offline_runners() {
  local id
  registered_runners | awk -v p="$prefix-" 'index($1, p) == 1 && $3 == "offline" { print $4 }' | while read -r id; do
    gh api -X DELETE "repos/$repo/actions/runners/$id" >/dev/null 2>&1 || true
  done
}

# --- Containers -------------------------------------------------------------------------------------------------------
running() { [[ -n "$("${compose[@]}" ps -q --status running "$1" 2>/dev/null)" ]]; }

# workdir_of <service>
workdir_of() { if [[ "$1" == runner ]]; then echo "$RUNNER_WORKDIR"; else echo "$RUNNER_LIGHT_WORKDIR"; fi; }

# empty_workdir <service>: the work directory is owned by root (the runner runs as root): it is emptied through a
# throw-away container of the same service, which mounts it at the same path.
empty_workdir() {
  # shellcheck disable=SC2016  # the variable is expanded by the shell inside the container
  RUNNER_TOKEN="" ACCESS_TOKEN="" RUNNER_RESTART=no "${compose[@]}" run --rm -T --no-deps --entrypoint sh "$1" \
    -c 'rm -rf "${RUNNER_WORKDIR:?}"/* "${RUNNER_WORKDIR:?}"/.[!.]* 2>/dev/null; true' >/dev/null 2>&1 || true
}

# start <service> <name>: a new container registered as <name>.
start() {
  local service=$1 name=$2 token
  token="$(registration_token)"
  [[ -n "$token" ]] || return 1
  if [[ "$service" == runner ]]; then
    RUNNER_TOKEN="$token" ACCESS_TOKEN="" RUNNER_RESTART=no RUNNER_EPHEMERAL="" RUNNER_DISABLE_DEREGISTRATION=true \
      RUNNER_NAME="$name" "${compose[@]}" up -d --force-recreate --no-deps runner >/dev/null 2>&1
  else
    RUNNER_TOKEN="$token" ACCESS_TOKEN="" RUNNER_RESTART=no RUNNER_EPHEMERAL="" RUNNER_DISABLE_DEREGISTRATION=true \
      RUNNER_LIGHT_NAME="$name" "${compose[@]}" up -d --force-recreate --no-deps runner-light >/dev/null 2>&1
  fi
}

# teardown <service> <name>: stop and remove the container, delete its registration, empty its work directory.
teardown() {
  local service=$1 name=$2
  "${compose[@]}" stop -t 30 "$service" >/dev/null 2>&1 || true
  "${compose[@]}" rm -f -s "$service" >/dev/null 2>&1 || true
  [[ -z "$name" ]] || delete_registration "$name"
  empty_workdir "$service"
}

# prune_engine: between sessions, dangling images (every CI image build leaves the previous one untagged) and build
# cache older than 72 h; tagged images, volumes and running containers are never touched.
prune_engine() {
  [[ "$prune" != "false" ]] || return 0
  docker image prune -f --filter until=72h >/dev/null 2>&1 || true
  docker builder prune -f --filter until=72h >/dev/null 2>&1 || true
}

# shellcheck disable=SC2016  # $4 is expanded by awk inside the container
low_disk_warning() {
  local free
  free="$(docker run --rm --entrypoint sh -v "$RUNNER_WORKDIR:/w" "${compose_image}" -c 'df -Pk /w | awk "NR==2 { print \$4 }"' 2>/dev/null || true)"
  [[ "$free" =~ ^[0-9]+$ ]] || return 0
  ((free >= 8 * 1024 * 1024)) || log "warning: only $((free / 1024 / 1024)) GiB free on the engine disk; a cache-cold verify needs about 8 GiB" >&2
}

# --- Main loop --------------------------------------------------------------------------------------------------------
cd "$DIR"
compose_image="$(awk '/^ *image: myoung34\/github-runner/ { print $2; exit }' docker-compose.yml)"
names=()
last_active=()
for i in "${!services[@]}"; do
  names[i]=""
  last_active[i]=0
  # A previous run of the script may have been killed with its containers still up.
  teardown "${services[i]}" ""
done
delete_offline_runners
low_disk_warning
log "waiting for queued runs (runners: ${services[*]}; idle timeout ${idle_timeout}s, poll ${poll}s)"
sessions=0    # runner containers removed so far (--once ends after the first session)
dirty=false   # a session ended since the last prune
while true; do
  pending="$(pending_runs)"
  want=false
  [[ "$stop" == "true" || -z "$pending" || "$pending" -eq 0 ]] || want=true
  [[ "$ONCE" == "false" || "$sessions" -eq 0 ]] || want=false

  # One listing of the registered runners per poll serves every busy check below.
  runners_now=""
  for i in "${!services[@]}"; do
    if [[ -n "${names[i]}" ]]; then runners_now="$(registered_runners)"; break; fi
  done

  active=0
  for i in "${!services[@]}"; do
    service="${services[i]}"
    name="${names[i]}"
    if [[ -z "$name" ]]; then
      [[ "$want" == "true" ]] || continue
      name="$prefix-${service#runner}-$(date +%s)-$RANDOM"
      name="${name/--/-}"
      if start "$service" "$name"; then
        log "$pending run(s) pending: started $service as $name"
        names[i]="$name"
        last_active[i]=$SECONDS
        active=$((active + 1))
      else
        log "$service did not start" >&2
        teardown "$service" "$name"
      fi
      continue
    fi

    keep=false
    if running "$service"; then
      # An unanswered API call (no busy flag) counts as activity, so a network problem never stops a runner in the middle
      # of a job; a busy runner is never stopped, not even on Ctrl-C.
      busy="$(printf '%s\n' "$runners_now" | awk -v n="$name" '$1 == n { print $2 }')"
      if [[ "$busy" != "false" ]]; then
        last_active[i]=$SECONDS
        keep=true
      elif [[ "$stop" == "false" ]]; then
        [[ "$want" == "false" && -n "$pending" ]] || last_active[i]=$SECONDS
        if ((SECONDS - last_active[i] < idle_timeout)); then
          keep=true
        else
          log "$service $name idle for ${idle_timeout}s: stopping it"
        fi
      fi
    else
      log "$service $name exited on its own" >&2
    fi
    if [[ "$keep" == "true" ]]; then
      active=$((active + 1))
      continue
    fi
    teardown "$service" "$name"
    names[i]=""
    sessions=$((sessions + 1))
    dirty=true
    log "$service $name removed"
  done

  if ((active == 0)); then
    if [[ "$dirty" == "true" ]]; then
      prune_engine
      low_disk_warning
      dirty=false
    fi
    [[ "$stop" == "false" ]] || break
    [[ "$ONCE" == "false" || "$sessions" -eq 0 ]] || break
  fi
  sleep "$poll"
done
log "stopped"
