# shellcheck shell=bash
# Platform lifecycle for scripts/dev-env.sh (sourced, never executed): Compose calls, always with
# `-p ecommerce-platform` and explicit profiles, from platform/compose; health waiting from `compose ps --format
# json` (data-model.md section 5.4); smoke checks; the status table; the foreign-container warning and the
# data-loss confirmation. Nothing is ever addressed by container name pattern, `--all` or a filter other than the
# project label of a read-only `ps`. Bash 3.2 compatible.

PROJECT="ecommerce-platform"
CI_RUNNER_PROJECT="ci-runner"
CI_RUNNER_DIR="$REPO_ROOT/platform/ci-runner"
PROFILES=(--profile core --profile observability)
WAIT_SECONDS="${DEV_ENV_WAIT_SECONDS:-600}"
POLL_SECONDS="${DEV_ENV_POLL_SECONDS:-3}"
WAIT_PENDING=""
CATALOG_PATH="/api/v1/catalog/products"
BROKER_HEARTBEAT="http://localhost:9292/diagnostic/status/heartbeat"
REGISTRY_URL="https://localhost:5443/v2/"

# enable_runner_host: the ci profile joins every platform Compose call.
enable_runner_host() { PROFILES=(--profile core --profile observability --profile ci); }

# active_profiles: the profile names in use, space separated.
active_profiles() {
  if [ "$RUNNER_HOST" = 1 ]; then printf 'core observability ci\n'; else printf 'core observability\n'; fi
}

# pc ARGS...: read-only platform Compose call (project name, profiles, from platform/compose so .env is read).
pc() {
  (cd "$COMPOSE_DIR" && "${COMPOSE[@]}" -p "$PROJECT" "${PROFILES[@]}" "$@")
}

pc_describe() { printf '%s -p %s %s %s\n' "${COMPOSE[*]}" "$PROJECT" "${PROFILES[*]}" "$*"; }

# pc_mutate ARGS...: mutating platform Compose call; printed and skipped in a dry run.
pc_mutate() {
  if [ "$DRY_RUN" = 1 ]; then
    dry_run_line "$(pc_describe "$@")"
    return 0
  fi
  vlog "+ $(pc_describe "$@")"
  pc "$@"
}

# compose_services PROFILE...: the services of the given profiles.
compose_services() {
  local args=() p
  for p in "$@"; do args+=(--profile "$p"); done
  (cd "$COMPOSE_DIR" && "${COMPOSE[@]}" -p "$PROJECT" "${args[@]}" config --services 2>/dev/null) || true
}

# platform_ps_json: `compose ps --format json` normalised to one JSON array (JSON lines or array input).
platform_ps_json() {
  local raw
  raw="$(pc ps --format json 2>/dev/null || true)"
  [ -n "$raw" ] || { printf '[]\n'; return 0; }
  printf '%s\n' "$raw" | jq -s -c 'map(if type == "array" then .[] else . end)' 2>/dev/null || printf '[]\n'
}

# component_state JSON SERVICE: healthy | starting | unhealthy | stopped (data-model.md section 5.4).
component_state() {
  printf '%s' "$1" | jq -r --arg s "$2" '
    [.[] | select(.Service == $s)] |
    if length == 0 then "stopped" else
      (.[0] | (.Health // "") as $h | (.State // "") as $st |
        if $h == "healthy" then "healthy"
        elif $h == "starting" or $st == "restarting" then "starting"
        elif $h == "unhealthy" then "unhealthy"
        elif $st == "running" then "healthy"
        else "stopped" end)
    end'
}

# wait_platform_healthy SERVICES...: until every service is healthy or WAIT_SECONDS elapsed (WAIT_PENDING lists the rest).
wait_platform_healthy() {
  local deadline=$((SECONDS + WAIT_SECONDS)) json s state
  while true; do
    json="$(platform_ps_json)"
    WAIT_PENDING=""
    for s in "$@"; do
      state="$(component_state "$json" "$s")"
      [ "$state" = healthy ] || WAIT_PENDING="$WAIT_PENDING $s($state)"
    done
    [ -n "$WAIT_PENDING" ] || return 0
    [ "$SECONDS" -lt "$deadline" ] || return 1
    sleep "$POLL_SECONDS"
  done
}

# published_services JSON: services that publish a host port, space separated and sorted.
published_services() {
  printf '%s' "$1" |
    jq -r '.[] | select(((.Publishers // []) | map(select(.PublishedPort != 0)) | length) > 0) | .Service' |
    sort -u | tr '\n' ' ' | sed 's/ $//'
}

# fetch_headers URL: response headers (status line first), empty when the connection fails.
fetch_headers() {
  with_timeout 6 curl -s -o /dev/null -D - --connect-timeout "$PROBE_TIMEOUT" --max-time 5 "$1" 2>/dev/null | tr -d '\r' || true
}

smoke_fix() { fix_line "$OS" "scripts/dev-env.sh status; $(pc_describe logs "$1")"; }

# The first API request after a (re)start can outlast one probe while the JVMs warm up, so the entry check is
# retried for DEV_ENV_SMOKE_SECONDS (default 60) before it fails.
smoke_entry() {
  local status deadline=$((SECONDS + ${DEV_ENV_SMOKE_SECONDS:-60}))
  while :; do
    status="$(http_status "http://localhost:$GATEWAY_PORT_EFFECTIVE$CATALOG_PATH")"
    [ "$status" = 200 ] && break
    [ "$SECONDS" -lt "$deadline" ] || break
    sleep "${DEV_ENV_POLL_SECONDS:-3}"
  done
  if [ "$status" = 200 ]; then
    check_line PASS entry "$status" "200 from gateway"
  else
    check_line FAIL entry "$status" "200 from gateway"
    smoke_fix gateway
  fi
}

smoke_isolation() {
  local json published allowed="gateway grafana mailpit" s bad=""
  json="$(platform_ps_json)"
  published="$(published_services "$json")"
  [ "$RUNNER_HOST" = 0 ] || allowed="$allowed pact-broker"
  for s in $published; do
    case " $allowed " in *" $s "*) ;; *) bad="$bad $s" ;; esac
  done
  if [ -z "$bad" ]; then
    check_line PASS isolation "${published:-nothing published}" "only gateway published"
  else
    check_line FAIL isolation "$published (unexpected:$bad)" "only gateway published"
    fix_line "$OS" "remove the ports: entry of$bad in platform/compose/docker-compose.yml (FR-023)"
  fi
}

smoke_storefront() {
  local headers status ctype csp found
  headers="$(fetch_headers "http://localhost:$GATEWAY_PORT_EFFECTIVE/")"
  status="$(printf '%s\n' "$headers" | head -n1 | awk '{ print $2 }')"
  ctype="$(printf '%s\n' "$headers" | grep -i '^content-type:' | head -n1 | cut -d: -f2- | tr -d ' ')"
  csp="$(printf '%s\n' "$headers" | grep -i '^content-security-policy:' | head -n1 | cut -d: -f2-)"
  found="${status:-000}"
  case "$ctype" in text/html*) found="$found text/html" ;; *) found="$found ${ctype:-no content-type}" ;; esac
  if [ -z "$csp" ]; then found="$found, CSP missing"
  else
    case "$csp" in *unsafe-inline*) found="$found, CSP has unsafe-inline" ;; *) found="$found, CSP strict" ;; esac
  fi
  if [ "$status" = 200 ] && [ -n "$csp" ]; then
    case "$ctype:$csp" in
      text/html*:*unsafe-inline*) ;;
      text/html*:*) check_line PASS storefront "$found" "GET / serves the app"; return 0 ;;
    esac
  fi
  check_line FAIL storefront "$found" "GET / serves the app"
  smoke_fix storefront
}

smoke_broker() {
  local status
  status="$(http_status "$BROKER_HEARTBEAT")"
  if [ "$status" = 200 ]; then
    check_line PASS broker "$status" "200 from pact broker"
  else
    check_line FAIL broker "$status" "200 from pact broker"
    smoke_fix pact-broker
  fi
}

smoke_ci_reg() {
  local status
  status="$(with_timeout 6 curl -s -k -o /dev/null -w '%{http_code}' --connect-timeout "$PROBE_TIMEOUT" --max-time 5 "$REGISTRY_URL" 2>/dev/null || true)"
  if [ -n "$status" ] && [ "$status" != 000 ]; then
    check_line PASS ci-reg "HTTP $status" "registry answers"
  else
    check_line FAIL ci-reg "${status:-000}" "registry answers"
    fix_line "$OS" "platform/ci-runner/README.md (Setup): scripts/init-registry.sh, then ${COMPOSE[*]} -p $CI_RUNNER_PROJECT -f platform/ci-runner/docker-compose.yml up -d registry"
  fi
}

# run_smoke_checks: the smoke lines; returns 1 when one failed.
run_smoke_checks() {
  local before="$CHECK_FAIL"
  smoke_entry
  smoke_isolation
  smoke_storefront
  if [ "$RUNNER_HOST" = 1 ]; then
    smoke_broker
    smoke_ci_reg
  fi
  [ "$CHECK_FAIL" = "$before" ]
}

print_addresses() {
  printf 'addresses\n'
  printf '  %-11s %s\n' storefront "http://localhost:$GATEWAY_PORT_EFFECTIVE/"
  printf '  %-11s %s\n' api "http://localhost:$GATEWAY_PORT_EFFECTIVE$CATALOG_PATH"
  printf '  %-11s %s\n' grafana "http://localhost:3000"
  printf '  %-11s %s\n' mailpit "http://localhost:8025"
  if [ "$RUNNER_HOST" = 1 ]; then
    printf '  %-11s %s\n' broker "http://localhost:9292"
    printf '  %-11s %s\n' registry "$REGISTRY_URL"
  fi
}

print_runner_host_pointer() {
  [ "$RUNNER_HOST" = 1 ] || return 0
  printf 'runner host: register the runner as described in platform/ci-runner/README.md ("Register the runner")\n'
}

# --- private registry of the CI runner stack (runner-host mode only)
ci_env_file() {
  if [ -f "$CI_RUNNER_DIR/.env" ]; then printf '%s\n' "$CI_RUNNER_DIR/.env"; else printf '%s\n' "$CI_RUNNER_DIR/.env.example"; fi
}

registry_compose() {
  "${COMPOSE[@]}" -p "$CI_RUNNER_PROJECT" -f "$CI_RUNNER_DIR/docker-compose.yml" --env-file "$(ci_env_file)" "$@"
}

registry_describe() {
  printf '%s -p %s -f %s --env-file %s %s\n' "${COMPOSE[*]}" "$CI_RUNNER_PROJECT" "$CI_RUNNER_DIR/docker-compose.yml" "$(ci_env_file)" "$*"
}

registry_mutate() {
  if [ "$DRY_RUN" = 1 ]; then
    dry_run_line "$(registry_describe "$@")"
    return 0
  fi
  vlog "+ $(registry_describe "$@")"
  registry_compose "$@"
}

registry_state() {
  local raw
  raw="$(registry_compose ps --format json 2>/dev/null || true)"
  [ -n "$raw" ] || { printf 'stopped\n'; return 0; }
  component_state "$(printf '%s\n' "$raw" | jq -s -c 'map(if type == "array" then .[] else . end)' 2>/dev/null || echo '[]')" registry
}

wait_registry_healthy() {
  local deadline=$((SECONDS + WAIT_SECONDS)) state
  while true; do
    state="$(registry_state)"
    [ "$state" != healthy ] || return 0
    [ "$SECONDS" -lt "$deadline" ] || { WAIT_PENDING="$WAIT_PENDING registry($state)"; return 1; }
    sleep "$POLL_SECONDS"
  done
}

# --- start, wait, smoke: shared by init --start, update and reset. Returns 1 (exit 4 for the caller) on failure.
start_platform() {
  local up_log rc=0 services
  export COMPOSE_PARALLEL_LIMIT="${COMPOSE_PARALLEL_LIMIT:-1}"
  [ "$ENGINE" != podman ] || export BUILDAH_FORMAT="${BUILDAH_FORMAT:-docker}"
  if [ "$DRY_RUN" = 1 ]; then
    pc_mutate up -d --build
    [ "$RUNNER_HOST" = 0 ] || registry_mutate up -d registry
    return 0
  fi
  up_log="$(mktemp "${TMPDIR:-/tmp}/dev-env-up.XXXXXX")"
  TMP_FILES="$TMP_FILES $up_log"
  if [ "$VERBOSE" = 1 ]; then
    pc_mutate up -d --build || rc=$?
  else
    pc_mutate up -d --build >"$up_log" 2>&1 || rc=$?
  fi
  if [ "$rc" != 0 ]; then
    [ "$VERBOSE" = 1 ] || tail -n 40 "$up_log" >&2
    check_line FAIL up "exit $rc" "compose up succeeds"
    smoke_fix gateway
    return 1
  fi
  if [ "$RUNNER_HOST" = 1 ]; then
    if ! registry_mutate up -d registry >"$up_log" 2>&1; then
      tail -n 40 "$up_log" >&2
      check_line FAIL ci-reg "compose up failed" "registry answers"
      return 1
    fi
  fi
  # shellcheck disable=SC2046  # the profile names are word-split on purpose
  services="$(compose_services $(active_profiles) | tr '\n' ' ')"
  # shellcheck disable=SC2086  # the service list is word-split on purpose
  if ! wait_platform_healthy $services; then
    check_line FAIL health "not healthy:$WAIT_PENDING" "all components healthy within ${WAIT_SECONDS}s"
    fix_line "$OS" "scripts/dev-env.sh status; $(pc_describe logs --tail 100)"
    return 1
  fi
  if [ "$RUNNER_HOST" = 1 ] && ! wait_registry_healthy; then
    check_line FAIL health "not healthy:$WAIT_PENDING" "all components healthy within ${WAIT_SECONDS}s"
    return 1
  fi
  run_smoke_checks || return 1
  print_addresses
  print_runner_host_pointer
}

# platform_has_containers: the project has at least one container (any state).
platform_has_containers() {
  [ "$(platform_ps_json | jq 'length')" -gt 0 ]
}

# warn_foreign_containers (C3): running containers that do not belong to the platform project; never touched.
warn_foreign_containers() {
  [ -n "$ENGINE_BIN" ] || return 0
  local all ours foreign="" n found
  all="$(with_timeout "$PROBE_TIMEOUT" "$ENGINE_BIN" ps --format '{{.Names}}' 2>/dev/null || true)"
  ours="$(with_timeout "$PROBE_TIMEOUT" "$ENGINE_BIN" ps --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Names}}' 2>/dev/null || true)"
  for n in $all; do
    found=0
    for o in $ours; do [ "$n" = "$o" ] && found=1; done
    [ "$found" = 1 ] || foreign="$foreign, $n"
  done
  [ -z "$foreign" ] || printf 'warning: containers outside the platform are running: %s\n' "${foreign#, }"
}

# confirm_data_loss: the exact prompt; exits 0 on any answer but `yes`, 2 without a terminal and without --yes.
confirm_data_loss() {
  if [ "$DRY_RUN" = 1 ]; then
    dry_run_line "ask the data-loss confirmation (compose project '$PROJECT')"
    return 0
  fi
  if [ "$YES" = 1 ]; then
    printf 'confirmed by --yes: deleting platform data\n'
    return 0
  fi
  if ! is_interactive; then
    error "$SUBCOMMAND deletes data; rerun with --yes"
    exit 2
  fi
  local answer
  printf '%s\n' "This will DELETE all data of the platform (compose project '$PROJECT'): databases, Kafka, Loki, Tempo, Prometheus, Grafana and Pact Broker volumes. Containers, images and volumes of other projects are not touched."
  printf "Type 'yes' to continue, anything else aborts [yes/N]: "
  read_answer answer
  if [ "$answer" != yes ]; then
    printf 'aborted: nothing was changed\n'
    exit 0
  fi
}

# print_status_table: components of the platform project with their state; returns 1 when a core one is not healthy.
print_status_table() {
  local json core all s state unhealthy=0
  json="$(platform_ps_json)"
  core="$(compose_services core | tr '\n' ' ')"
  # shellcheck disable=SC2046  # the profile names are word-split on purpose
  all="$(compose_services $(active_profiles) | tr '\n' ' ')"
  printf "platform (compose project '%s')\n" "$PROJECT"
  if [ "$(printf '%s' "$json" | jq 'length')" = 0 ]; then
    printf '  (no containers: the platform is down)\n'
    return 0
  fi
  for s in $all; do
    state="$(component_state "$json" "$s")"
    printf '  %-14s %s\n' "$s" "$state"
    case " $core " in *" $s "*) [ "$state" = healthy ] || unhealthy=1 ;; esac
  done
  if [ "$RUNNER_HOST" = 1 ]; then printf '  %-14s %s\n' "registry" "$(registry_state)"; fi
  [ "$unhealthy" = 0 ]
}

print_engine_resources() {
  [ -n "$ENGINE_BIN" ] || { printf 'engine: not reachable\n'; return 0; }
  local mem cpus disk
  mem="$(gib_of "$(engine_memory_bytes || echo 0)")"
  cpus="$(engine_cpus || echo '?')"
  disk="$(free_gib_of "$REPO_ROOT")"
  printf 'engine: %s %s, memory %s GiB, cpus %s, free disk %s GiB\n' "$ENGINE" "$ENGINE_VERSION" "$mem" "$cpus" "${disk:-?}"
}
