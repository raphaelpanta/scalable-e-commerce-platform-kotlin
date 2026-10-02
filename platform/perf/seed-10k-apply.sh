#!/usr/bin/env bash
# Loads (or removes) the 10,000-product performance dataset in the catalogue database of the running Compose stack
# (T115). The database port is not published (FR-023), so psql runs inside the catalog-db container and the SQL file
# is piped through stdin; the credentials come from platform/compose/env/catalog.env.
#
# Usage: seed-10k-apply.sh [--remove] [-h]
#   --remove  run seed-10k-remove.sql instead (deletes the PERF-* products and their reservations)
#
# Environment: COMPOSE_CMD overrides the Compose command (default: "docker compose"; e.g. "podman compose" or
# "docker-compose"). The stack must be running with SEED=true (the dataset uses the seed categories).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$(cd "$SCRIPT_DIR/../compose" && pwd)"
ENV_FILE="$COMPOSE_DIR/env/catalog.env"
SQL_FILE="$SCRIPT_DIR/seed-10k.sql"

usage() {
  sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
  case "$arg" in
    --remove) SQL_FILE="$SCRIPT_DIR/seed-10k-remove.sql" ;;
    -h | --help) usage; exit 0 ;;
    *) echo "unknown option: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

# env_value <KEY>: value of KEY=value in catalog.env (no sourcing: the file is data, not a script).
env_value() {
  local value
  value="$(grep -E "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2-)"
  if [[ -z "$value" ]]; then
    echo "FAIL: $1 is not set in $ENV_FILE" >&2
    exit 2
  fi
  printf '%s' "$value"
}

[[ -f "$ENV_FILE" ]] || { echo "FAIL: $ENV_FILE not found" >&2; exit 2; }
DB_USER="$(env_value POSTGRES_USER)"
DB_NAME="$(env_value POSTGRES_DB)"
DB_PASSWORD="$(env_value POSTGRES_PASSWORD)"

# Word splitting of COMPOSE_CMD is intended ("docker compose" is two words).
# shellcheck disable=SC2206
COMPOSE=(${COMPOSE_CMD:-docker compose})

echo "applying $(basename "$SQL_FILE") to database '$DB_NAME' as '$DB_USER' (service catalog-db)"
(
  cd "$COMPOSE_DIR"
  "${COMPOSE[@]}" --profile core exec -T -e "PGPASSWORD=$DB_PASSWORD" catalog-db \
    psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" <"$SQL_FILE"
)
