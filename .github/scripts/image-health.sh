#!/usr/bin/env bash
# T149 (feature 004, user story 9, AC3): start the freshly built image of one service with the minimum environment it
# needs to boot WITHOUT the rest of the platform, wait up to HEALTH_TIMEOUT seconds (default 90) for the management port's
# readiness group `/actuator/health/readiness` to answer {"status":"UP"}, print the last log lines on failure and always
# stop what it started.
#
# Usage: image-health.sh <service> <image>
#   service  gateway | identity | catalog | cart | order | payment | notification
#   image    the image reference to run, e.g. cart:3f2a... (it must already exist in the local engine)
#
# Why this is enough (docs/service-conventions.md section 2, docs/ci-cd.md "Start-and-health check"):
#   - a service reports ready once it has started and migrated its own database (catalog's readiness group also probes
#     PostgreSQL with a two-second timeout; Kafka, the identity JWKS and the observability stack are not part of it), so
#     the script starts ONE throw-away postgres:18-alpine sidecar on a private network, with random credentials, and
#     points the service at it through <CTX>_DB_HOST/_DB_USER/_DB_PASSWORD; Flyway migrates the empty database
#     (SEED=false). INTERNAL_API_TOKEN is random. Kafka and the OTLP collector stay on their localhost defaults
#     (unreachable: the producer and the topic admin only log warnings; SPRING_KAFKA_ADMIN_* below shortens that wait);
#   - the gateway has no database and needs nothing: it starts alone with its localhost defaults;
#   - the probe runs INSIDE the container (bash /dev/tcp to 127.0.0.1:8081, the same check as the image HEALTHCHECK),
#     so it works with the runner's host network, a remote Docker engine or Podman without publishing any port.
# Environment: HEALTH_TIMEOUT (seconds, default 90), POSTGRES_IMAGE (default postgres:18-alpine, the tag Compose uses),
#   GITHUB_RUN_ID / GITHUB_RUN_ATTEMPT (make the container names unique per run; "local" otherwise).
set -euo pipefail

service="${1:-}"
image="${2:-}"
[ -n "$service" ] && [ -n "$image" ] || { sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//' >&2; exit 64; }
case "$service" in
  gateway | identity | catalog | cart | order | payment | notification) ;;
  *) echo "::error::unknown service '$service'" >&2; exit 64 ;;
esac

timeout="${HEALTH_TIMEOUT:-90}"
postgres_image="${POSTGRES_IMAGE:-postgres:18-alpine}"
suffix="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}-$$-$service"
net="health-net-$suffix"
db="health-db-$suffix"
app="health-app-$suffix"
ctx_upper="$(printf '%s' "$service" | tr '[:lower:]' '[:upper:]')"
started_db=false
started_net=false
result=failure

# shellcheck disable=SC2329  # invoked through the EXIT trap
cleanup() {
  local status=$?
  if [ "$result" != success ]; then
    echo "::group::last log lines of $service ($image)"
    docker logs --tail 80 "$app" 2>&1 || true
    echo "::endgroup::"
    if [ "$started_db" = true ]; then
      echo "::group::last log lines of the database sidecar"
      docker logs --tail 20 "$db" 2>&1 || true
      echo "::endgroup::"
    fi
  fi
  docker rm -f -v "$app" >/dev/null 2>&1 || true
  [ "$started_db" = false ] || docker rm -f -v "$db" >/dev/null 2>&1 || true
  [ "$started_net" = false ] || docker network rm "$net" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

random() { openssl rand -hex 16; }
internal_token="$(random)"
echo "::add-mask::$internal_token"

# Same shape as the Compose environment (platform/compose/docker-compose.yml): memory bound, credentials from the
# environment only, seed data off.
run_args=(-d --name "$app" --memory 768m
  -e "INTERNAL_API_TOKEN=$internal_token" -e SEED=false
  -e SPRING_KAFKA_ADMIN_AUTO_CREATE=false -e SPRING_KAFKA_ADMIN_OPERATION_TIMEOUT=5s)

if [ "$service" != gateway ]; then
  db_password="$(random)"
  echo "::add-mask::$db_password"
  docker network create "$net" >/dev/null
  started_net=true
  docker run -d --name "$db" --network "$net" --memory 256m --tmpfs /var/lib/postgresql \
    -e POSTGRES_USER=app -e "POSTGRES_PASSWORD=$db_password" -e "POSTGRES_DB=$service" "$postgres_image" >/dev/null
  started_db=true
  for _ in $(seq 1 30); do
    docker exec "$db" pg_isready -q -U app -d "$service" && break
    sleep 1
  done
  docker exec "$db" pg_isready -q -U app -d "$service" || { echo "::error::the postgres sidecar did not become ready"; exit 1; }
  run_args+=(--network "$net" -e "${ctx_upper}_DB_HOST=$db" -e "${ctx_upper}_DB_USER=app" -e "${ctx_upper}_DB_PASSWORD=$db_password")
fi

docker run "${run_args[@]}" "$image" >/dev/null

probe() {
  docker exec "$app" bash -c \
    'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/health/readiness HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && cat <&3' 2>/dev/null
}

deadline=$((SECONDS + timeout))
while true; do
  if [ "$(docker inspect -f '{{.State.Status}}' "$app" 2>/dev/null || echo gone)" != running ]; then
    echo "::error::the $service container exited before it reported UP"
    exit 1
  fi
  if body="$(probe)" && printf '%s' "$body" | grep -q '"status":"UP"'; then
    result=success
    echo "$service is UP after $((timeout - (deadline - SECONDS))) s: $(printf '%s' "$body" | tail -n 1)"
    exit 0
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "::error::$service did not report {\"status\":\"UP\"} on :8081/actuator/health/readiness within ${timeout}s"
    exit 1
  fi
  sleep 2
done
