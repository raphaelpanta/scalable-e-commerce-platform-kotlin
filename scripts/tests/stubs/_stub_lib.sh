# shellcheck shell=bash
# Shared code of the fake binaries that scripts/tests/test_dev_env_*.sh put first on PATH (sourced, never executed).
# Every stub appends one line "<name> <args>" to $STUB_LOG, then answers from STUB_* variables; nothing is ever
# executed for real except `jq` and `git`, which pass through to the binaries the fixture recorded.
#
#   STUB_LOG                   file every invocation is appended to (required)
#   STUB_STATE                 directory; `absent` lists the tools that behave as not installed (installs remove them)
#   STUB_ABSENT                initial space-separated list of absent tools (used when STUB_STATE/absent is missing)
#   STUB_ENGINE                docker | podman | podman-socket (Podman behind the Docker socket) | none
#   STUB_ENGINE_VERSION        engine server version (default 28.1.1)
#   STUB_ENGINE_MEMORY_GIB     engine memory in GiB (default 12)
#   STUB_ENGINE_CPUS           engine CPUs (default 6)
#   STUB_ENGINE_ROOT_DIR       DockerRootDir reported by `info` (default /var/lib/docker)
#   STUB_COMPOSE_VERSION       Compose version without the leading v (default 2.29.1)
#   STUB_COMPOSE_SERVICES      services printed by `compose config --services` (default: core + observability)
#   STUB_COMPOSE_PS_JSON       output of `compose ps --format json` (default: every service healthy)
#   STUB_COMPOSE_PS_AFTER_UP   when set, `ps` prints this instead once an `up` was logged (state transitions)
#   STUB_COMPOSE_UP_FAIL       1: `compose up` exits 1
#   STUB_RUNNING_CONTAINERS    "name=project name=project ..." for `<engine> ps` (default: platform containers only)
#   STUB_PORT_IN_USE           space-separated host ports that accept a TCP connection (curl exit 0 instead of 7)
#   STUB_NETWORK               0: curl cannot reach the internet (exit 6)
#   STUB_ENTRY_STATUS          status of GET /api/v1/catalog/products through the gateway (default 200)
#   STUB_STOREFRONT_HEADERS    response headers of GET / (default: 200, text/html, strict CSP)
#   STUB_BROKER_STATUS         status of the Pact Broker heartbeat (default 200)
#   STUB_REGISTRY_STATUS       status of the private registry /v2/ (default 401)
#   STUB_JAVA_VERSION, STUB_NODE_VERSION, STUB_NPM_VERSION, STUB_GIT_VERSION, STUB_GITLEAKS_VERSION,
#   STUB_CURL_VERSION, STUB_JQ_VERSION, STUB_OPENSSL_VERSION      reported versions
#   STUB_OPENSSL_FAIL          1: every openssl command fails (generation errors)
#   STUB_OS                    Darwin | Linux for `uname -s` (default Darwin)
#   STUB_DF_AVAIL_KB           free kilobytes reported by `df -Pk` (default 60 GiB)
#   STUB_REAL_JQ, STUB_REAL_GIT   the real binaries the pass-through stubs execute

STUB_NAME="$(basename "$0")"

stub_log() {
  printf '%s %s\n' "$STUB_NAME" "$*" >>"${STUB_LOG:?STUB_LOG must be set}"
}

stub_absent_list() {
  if [ -n "${STUB_STATE:-}" ] && [ -f "$STUB_STATE/absent" ]; then
    cat "$STUB_STATE/absent"
  else
    printf '%s\n' "${STUB_ABSENT:-}"
  fi
}

# stub_is_absent TOOL: true when TOOL is listed as not installed.
stub_is_absent() {
  local tool
  for tool in $(stub_absent_list); do
    [ "$tool" = "$1" ] && return 0
  done
  return 1
}

# stub_not_found TOOL: behave like a shell that cannot find the command.
stub_not_found() {
  printf 'bash: %s: command not found\n' "$1" >&2
  exit 127
}

# stub_mark_installed TOOL...: remove tools from the absent list (installs).
stub_mark_installed() {
  [ -n "${STUB_STATE:-}" ] || return 0
  mkdir -p "$STUB_STATE"
  local current="" tool keep="" t
  current="$(stub_absent_list)"
  for t in $current; do
    local drop=0
    for tool in "$@"; do [ "$t" = "$tool" ] && drop=1; done
    [ "$drop" = 1 ] || keep="$keep $t"
  done
  printf '%s\n' "$keep" >"$STUB_STATE/absent"
}

# stub_package_tools PACKAGE: the tools a package name provides.
stub_package_tools() {
  case "$1" in
    node@24 | nodejs | node) printf 'node npm' ;;
    docker-compose-plugin | docker-compose | podman-compose) printf 'compose' ;;
    npm) printf 'npm' ;;
    *) printf '%s' "$1" ;;
  esac
}

stub_engine() { printf '%s' "${STUB_ENGINE:-docker}"; }

STUB_CORE_SERVICES="gateway identity identity-db catalog catalog-db cart cart-db order order-db payment payment-db notification notification-db kafka mailpit"
STUB_OBSERVABILITY_SERVICES="otel-collector loki tempo prometheus grafana"
STUB_CI_SERVICES="pact-broker-db pact-broker"

stub_default_services() {
  # shellcheck disable=SC2086  # one service per word
  printf '%s\n' $STUB_CORE_SERVICES $STUB_OBSERVABILITY_SERVICES
}

# stub_profile_services ARGS...: the services of the `--profile` arguments (all core + observability when none).
stub_profile_services() {
  local a want="" list=""
  while [ "$#" -gt 0 ]; do
    a="$1"
    case "$a" in --profile) want="$want $2"; shift 2 ;; *) shift ;; esac
  done
  [ -n "$want" ] || want=" core observability"
  case "$want" in *" core"*) list="$list $STUB_CORE_SERVICES" ;; esac
  case "$want" in *" observability"*) list="$list $STUB_OBSERVABILITY_SERVICES" ;; esac
  case "$want" in *" ci"*) list="$list $STUB_CI_SERVICES" ;; esac
  # shellcheck disable=SC2086  # one service per word
  printf '%s\n' $list
}

# stub_default_ps_json: every default service running and healthy, gateway/grafana/mailpit published.
stub_default_ps_json() {
  local s port
  for s in $(stub_default_services); do
    port=0
    case "$s" in
      gateway) port="${GATEWAY_PORT:-8080}" ;;
      grafana) port=3000 ;;
      mailpit) port=8025 ;;
    esac
    if [ "$port" = 0 ]; then
      printf '{"Name":"ecommerce-platform-%s-1","Service":"%s","State":"running","Health":"healthy","Publishers":[]}\n' "$s" "$s"
    else
      printf '{"Name":"ecommerce-platform-%s-1","Service":"%s","State":"running","Health":"healthy","Publishers":[{"URL":"127.0.0.1","TargetPort":%s,"PublishedPort":%s,"Protocol":"tcp"}]}\n' \
        "$s" "$s" "$port" "$port"
    fi
  done
}

stub_up_logged() {
  [ -f "${STUB_LOG:-}" ] && grep -q ' up ' "$STUB_LOG"
}

# stub_compose ARGS...: the Compose provider (docker compose, docker-compose, podman compose).
stub_compose() {
  if stub_is_absent compose; then
    printf "docker: 'compose' is not a docker command.\n" >&2
    exit 1
  fi
  local a
  for a in "$@"; do
    case "$a" in
      version)
        printf 'Docker Compose version v%s\n' "${STUB_COMPOSE_VERSION:-2.29.1}"
        return 0 ;;
      config)
        # shellcheck disable=SC2086  # one service per word
        if [ -n "${STUB_COMPOSE_SERVICES+x}" ]; then printf '%s\n' $STUB_COMPOSE_SERVICES; else stub_profile_services "$@"; fi
        return 0 ;;
      ps)
        if [ -n "${STUB_COMPOSE_PS_AFTER_UP+x}" ] && stub_up_logged; then
          printf '%s\n' "$STUB_COMPOSE_PS_AFTER_UP"
        elif [ -n "${STUB_COMPOSE_PS_JSON+x}" ]; then
          printf '%s\n' "$STUB_COMPOSE_PS_JSON"
        else
          stub_default_ps_json
        fi
        return 0 ;;
      up)
        [ "${STUB_COMPOSE_UP_FAIL:-0}" = 1 ] && { echo "Error response from daemon: injected failure" >&2; exit 1; }
        return 0 ;;
      down | build | pull | stop | start | restart | rm | kill) return 0 ;;
    esac
  done
  return 0
}

# stub_engine_cli ENGINE ARGS...: `docker`/`podman` CLI shared behaviour. ENGINE is the binary being faked.
stub_engine_cli() {
  local me="$1"
  shift
  local engine
  engine="$(stub_engine)"
  case "$engine" in
    none) echo "Cannot connect to the ${me} daemon. Is the ${me} daemon running?" >&2; exit 1 ;;
    docker) [ "$me" = docker ] || { echo "Cannot connect to Podman. Please verify your connection to the Linux system" >&2; exit 125; } ;;
    podman) [ "$me" = podman ] || { echo "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?" >&2; exit 1; } ;;
    podman-socket) : ;; # both binaries answer; docker speaks to a Podman server
  esac
  local server_name="Docker Engine - Community"
  case "$engine" in podman | podman-socket) server_name="Podman Engine" ;; esac
  local version="${STUB_ENGINE_VERSION:-28.1.1}"
  local mem_bytes=$(( ${STUB_ENGINE_MEMORY_GIB:-12} * 1024 * 1024 * 1024 ))

  case "${1:-}" in
    compose) shift; stub_compose "$@" ;;
    info | system)
      [ "${1:-}" = system ] && shift
      [ "${1:-}" = info ] && shift
      local fmt=""
      while [ "$#" -gt 0 ]; do
        case "$1" in
          --format | -f) fmt="$2"; shift 2 ;;
          *) shift ;;
        esac
      done
      if [ -z "$fmt" ]; then
        printf 'Server:\n Server Version: %s\n Total Memory: %sGiB\n CPUs: %s\n' "$version" "${STUB_ENGINE_MEMORY_GIB:-12}" "${STUB_ENGINE_CPUS:-6}"
      else
        # Go-template placeholders replaced one by one, so a combined format works like the real CLI.
        printf '%s\n' "$fmt" | sed \
          -e "s|{{.MemTotal}}|$mem_bytes|g" \
          -e "s|{{.NCPU}}|${STUB_ENGINE_CPUS:-6}|g" \
          -e "s|{{.DockerRootDir}}|${STUB_ENGINE_ROOT_DIR:-/var/lib/docker}|g" \
          -e "s|{{.ServerVersion}}|$version|g"
      fi ;;
    version)
      shift
      local fmt=""
      while [ "$#" -gt 0 ]; do
        case "$1" in
          --format | -f) fmt="$2"; shift 2 ;;
          *) shift ;;
        esac
      done
      case "$fmt" in
        *Server.Version*) printf '%s\n' "$version" ;;
        *Client.Version*) printf '%s\n' "$version" ;;
        *json*) printf '{"Platform":{"Name":"%s"},"Version":"%s","ApiVersion":"1.47"}\n' "$server_name" "$version" ;;
        *) printf 'Client: %s\n Version: %s\n\nServer: %s\n Version: %s\n' "$server_name" "$version" "$server_name" "$version" ;;
      esac ;;
    ps)
      shift
      local filter_project="" fmt="" a
      while [ "$#" -gt 0 ]; do
        case "$1" in
          --filter | -f)
            case "$2" in label=com.docker.compose.project=*) filter_project="${2#label=com.docker.compose.project=}" ;; esac
            shift 2 ;;
          --format) fmt="$2"; shift 2 ;;
          *) shift ;;
        esac
      done
      local entry name project
      for entry in ${STUB_RUNNING_CONTAINERS-ecommerce-platform-gateway-1=ecommerce-platform}; do
        name="${entry%%=*}"
        project="${entry#*=}"
        [ "$project" = "$name" ] && project=""
        if [ -z "$filter_project" ] || [ "$filter_project" = "$project" ]; then
          printf '%s\n' "$name"
        fi
      done ;;
    machine) shift; printf '%s\n' "${STUB_ENGINE_MEMORY_GIB:-12}" ;;
    *) stub_log "unsupported: $*"; exit 0 ;;
  esac
}

# stub_version_tool TOOL DEFAULT_VERSION FORMAT: `tool --version` style answers; FORMAT uses %s for the version.
stub_version_tool() {
  local tool="$1" default="$2" fmt="$3" var value
  stub_is_absent "$tool" && stub_not_found "$tool"
  var="STUB_$(printf '%s' "$tool" | tr '[:lower:]-' '[:upper:]_')_VERSION"
  eval "value=\${$var:-$default}"
  # shellcheck disable=SC2059  # the caller passes a trusted format
  printf "$fmt\n" "$value"
}
