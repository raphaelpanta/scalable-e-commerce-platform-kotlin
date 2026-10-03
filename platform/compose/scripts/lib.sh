# shellcheck shell=bash
# shellcheck disable=SC2034  # variables are consumed by the scripts that source this file
# Written for bash 3.2+ (the macOS default): no mapfile, no empty-array expansion without a guard.
# Helpers shared by smoke.sh and resilience.sh. Sourced, never executed.

COMPOSE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PODMAN_COMPOSE_WARNING_LOGS=false

# Application containers whose image HEALTHCHECK must report "healthy".
APP_SERVICES=(gateway identity catalog cart order payment notification)

GATEWAY_URL="${GATEWAY_URL:-http://localhost:${GATEWAY_PORT:-8080}}"
CATALOG_PATH="/api/v1/catalog/products"

# Selects `docker compose` (v2 plugin) or the standalone `docker-compose`.
detect_compose() {
  if docker compose version >/dev/null 2>&1; then
    COMPOSE=(docker compose)
  elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE=(docker-compose)
  else
    echo "FAIL: neither 'docker compose' nor 'docker-compose' is available" >&2
    exit 2
  fi
}

# compose <args...>: runs Compose from the compose directory with the core profile (plus any extra profiles).
compose() {
  (cd "$COMPOSE_DIR" && "${COMPOSE[@]}" "${PROFILES[@]}" "$@")
}

# Creates .env from the example when missing (example values are safe for local use), and gives it a fresh
# identity signing key (IDENTITY_SIGNING_KEY, required by identity, never committed) when it has none.
ensure_env() {
  if [[ ! -f "$COMPOSE_DIR/.env" ]]; then
    cp "$COMPOSE_DIR/.env.example" "$COMPOSE_DIR/.env"
    CREATED_ENV=true
    log "created .env from .env.example"
  fi
  if ! grep -Eq '^IDENTITY_SIGNING_KEY=.+' "$COMPOSE_DIR/.env"; then
    local key
    key="$(openssl genpkey -algorithm ed25519 -outform DER | base64 | tr -d '\n')"
    grep -v '^IDENTITY_SIGNING_KEY=' "$COMPOSE_DIR/.env" >"$COMPOSE_DIR/.env.tmp" || true
    echo "IDENTITY_SIGNING_KEY=$key" >>"$COMPOSE_DIR/.env.tmp"
    mv "$COMPOSE_DIR/.env.tmp" "$COMPOSE_DIR/.env"
    log "generated IDENTITY_SIGNING_KEY in .env"
  fi
}

# log <message>: progress output, only when VERBOSE=1.
log() {
  if [[ "${VERBOSE:-0}" == "1" ]]; then
    echo "$*" >&2
  fi
}

# health_of <container-id>: healthy | unhealthy | starting | none (no health check) | exited
health_of() {
  docker inspect -f '{{if ne .State.Status "running"}}exited{{else if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$1" 2>/dev/null || echo exited
}

# wait_healthy <timeout-seconds> <service>...: waits until every container of each service is healthy.
# Returns 1 on timeout, naming the services that are not healthy yet in WAIT_PENDING.
wait_healthy() {
  local timeout="$1"
  shift
  local deadline=$((SECONDS + timeout))
  local service id state found
  while true; do
    WAIT_PENDING=()
    for service in "$@"; do
      found=false
      while read -r id; do
        [[ -n "$id" ]] || continue
        found=true
        state="$(health_of "$id")"
        if [[ "$state" != "healthy" ]]; then
          WAIT_PENDING+=("$service($state)")
        fi
      done < <(compose ps -q "$service" 2>/dev/null || true)
      if [[ "$found" == "false" ]]; then
        WAIT_PENDING+=("$service(no container)")
      fi
    done
    if [[ -z "${WAIT_PENDING[*]:-}" ]]; then
      return 0
    fi
    if ((SECONDS >= deadline)); then
      return 1
    fi
    sleep 3
  done
}

# http_status <url>: prints the HTTP status code, 000 when the connection fails.
http_status() {
  curl -s -o /dev/null -w '%{http_code}' --connect-timeout 2 --max-time 5 -H "X-Correlation-Id: platform-script-$$" "$1" || true
}
