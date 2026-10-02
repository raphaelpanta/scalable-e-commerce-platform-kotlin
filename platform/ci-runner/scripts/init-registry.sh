#!/usr/bin/env bash
# Prepares the private image registry of the CI stack (research.md section 5): a self-signed TLS certificate and an
# htpasswd file (bcrypt) under platform/ci-runner/data/, which docker-compose.yml mounts read-only into `registry`.
# Idempotent: existing files are kept unless --force is given. The registry password is read from REGISTRY_PASSWORD or
# generated; a generated password is printed once, on stdout, so that it can be stored as the repository secret
# REGISTRY_PASSWORD. It is never accepted as an argument and never written anywhere except the htpasswd hash.
#
# Usage: scripts/init-registry.sh [--host HOST[:PORT]] [--user NAME] [--days N] [--force] [--install-ca] [-h]
#   --host        registry name and port as jobs use it (default REGISTRY_HOST of .env, else localhost:5443); it is
#                 added to the certificate (DNS name or IP address) next to localhost and 127.0.0.1
#   --user        registry user (default REGISTRY_USERNAME of .env, else ci)
#   --days        certificate validity in days (default 825)
#   --force       regenerate the certificate and the htpasswd file
#   --install-ca  also trust the certificate in the host's Docker daemon (sudo; /etc/docker/certs.d/<host>/ca.crt,
#                 no daemon restart needed); without it the command is printed
# Needs: openssl and either htpasswd (apache2-utils / httpd-tools) or Docker (the httpd image supplies htpasswd).
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA="$DIR/data"
HTTPD_IMAGE="httpd:2.4-alpine@sha256:4e585da9d0125dec36d4500a9f5c5df7b2c0a01f67cb47865a91a4b05bdbec1b"

usage() { sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'; }
die() { echo "init-registry: $*" >&2; exit 1; }

# env_value KEY: value of KEY in .env (first match), empty when absent. The file is parsed, never sourced.
env_value() {
  [[ -f "$DIR/.env" ]] || return 0
  { grep -E "^$1=" "$DIR/.env" || true; } | head -n 1 | cut -d= -f2- | sed 's/^"//; s/"$//'
}

HOST="$(env_value REGISTRY_HOST)"
USER_NAME="$(env_value REGISTRY_USERNAME)"
DAYS=825
FORCE=false
INSTALL_CA=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) [[ $# -ge 2 ]] || die "--host needs a value"; HOST="$2"; shift ;;
    --user) [[ $# -ge 2 ]] || die "--user needs a value"; USER_NAME="$2"; shift ;;
    --days) [[ $# -ge 2 ]] || die "--days needs a value"; DAYS="$2"; shift ;;
    --force) FORCE=true ;;
    --install-ca) INSTALL_CA=true ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
  shift
done
HOST="${HOST:-localhost:5443}"
USER_NAME="${USER_NAME:-ci}"
[[ "$DAYS" =~ ^[0-9]+$ ]] || die "--days must be a number"
[[ "$HOST" =~ ^[A-Za-z0-9.-]+(:[0-9]+)?$ ]] || die "invalid host: $HOST"
[[ "$USER_NAME" =~ ^[A-Za-z0-9._-]+$ ]] || die "invalid user name: $USER_NAME"
command -v openssl >/dev/null 2>&1 || die "openssl is required"

NAME="${HOST%%:*}"
SAN="DNS:localhost,IP:127.0.0.1"
if [[ "$NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  [[ "$NAME" == "127.0.0.1" ]] || SAN="$SAN,IP:$NAME"
elif [[ "$NAME" != "localhost" ]]; then
  SAN="$SAN,DNS:$NAME"
fi

umask 077
mkdir -p "$DATA/certs" "$DATA/auth"

# --- TLS certificate (self-signed; it is its own CA, so it is also the file Docker has to trust)
if [[ -f "$DATA/certs/registry.crt" && -f "$DATA/certs/registry.key" && "$FORCE" == "false" ]]; then
  echo "certificate: kept ($DATA/certs/registry.crt); use --force to regenerate"
else
  conf="$(mktemp)"
  trap 'rm -f "$conf"' EXIT
  cat >"$conf" <<CONF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $NAME
[ext]
subjectAltName = $SAN
basicConstraints = critical,CA:TRUE
keyUsage = critical,digitalSignature,keyEncipherment,keyCertSign
extendedKeyUsage = serverAuth
CONF
  openssl req -x509 -newkey rsa:4096 -nodes -sha256 -days "$DAYS" -config "$conf" \
    -keyout "$DATA/certs/registry.key" -out "$DATA/certs/registry.crt" 2>/dev/null
  chmod 600 "$DATA/certs/registry.key"
  chmod 644 "$DATA/certs/registry.crt"
  echo "certificate: created for $SAN (valid $DAYS days)"
fi

# --- htpasswd file (bcrypt: the only format the registry accepts)
if [[ -f "$DATA/auth/htpasswd" && "$FORCE" == "false" ]]; then
  echo "htpasswd: kept ($DATA/auth/htpasswd); use --force to reset the password"
else
  GENERATED=false
  PASSWORD="${REGISTRY_PASSWORD:-}"
  if [[ -z "$PASSWORD" ]]; then
    PASSWORD="$(openssl rand -base64 30 | tr -d '=+/\n' | cut -c1-32)"
    GENERATED=true
  fi
  if command -v htpasswd >/dev/null 2>&1; then
    printf '%s' "$PASSWORD" | htpasswd -Bni "$USER_NAME" >"$DATA/auth/htpasswd"
  elif command -v docker >/dev/null 2>&1; then
    printf '%s' "$PASSWORD" | docker run --rm -i --entrypoint htpasswd "$HTTPD_IMAGE" -Bni "$USER_NAME" >"$DATA/auth/htpasswd"
  else
    die "need htpasswd or docker to create the htpasswd file"
  fi
  chmod 644 "$DATA/auth/htpasswd"
  echo "htpasswd: created for user $USER_NAME"
  if [[ "$GENERATED" == "true" ]]; then
    echo "registry password (shown once, store it as the repository secret REGISTRY_PASSWORD): $PASSWORD"
  fi
fi

# --- trust in the Docker daemon (it performs the login, pull and push, so it must trust the certificate)
CA_DIR="/etc/docker/certs.d/$HOST"
if [[ "$INSTALL_CA" == "true" ]]; then
  sudo install -D -m 644 "$DATA/certs/registry.crt" "$CA_DIR/ca.crt"
  echo "trusted by the Docker daemon: $CA_DIR/ca.crt"
else
  echo "trust it on every Docker host that logs in or pulls: sudo install -D -m 644 $DATA/certs/registry.crt $CA_DIR/ca.crt"
fi

cat <<NEXT
next: docker compose up -d registry
      repository variable REGISTRY_HOST=$HOST, secrets REGISTRY_USERNAME=$USER_NAME and REGISTRY_PASSWORD
      check: curl --cacert $DATA/certs/registry.crt -u $USER_NAME https://$HOST/v2/
NEXT
