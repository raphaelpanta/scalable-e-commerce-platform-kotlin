# shellcheck shell=bash
# shellcheck disable=SC2034  # this library sets variables (OS, PKG, ENGINE, ...) read by the other libraries
# Container engine and Compose provider detection for scripts/dev-env.sh (sourced, never executed).
# Detects Docker or Podman, Podman behind a Docker-compatible socket included, and the Compose v2 provider.
# Every probe is bounded by PROBE_TIMEOUT seconds. Bash 3.2 compatible.

PROBE_TIMEOUT=3
ENGINE=""         # docker | podman | "" (none reachable)
ENGINE_BIN=""     # CLI that answered: docker | podman
ENGINE_SHIM=0     # 1 when the docker CLI talks to a Podman server
ENGINE_VERSION=""
COMPOSE=()
COMPOSE_VERSION=""
OS=""             # macos | linux
PKG=""            # brew | apt | dnf | ""

# with_timeout SECONDS CMD...: run CMD, kill it after SECONDS (no coreutils `timeout` on macOS).
with_timeout() {
  local t="$1" pid wd rc=0
  shift
  "$@" &
  pid=$!
  ( sleep "$t"; kill "$pid" 2>/dev/null ) >/dev/null 2>&1 &
  wd=$!
  wait "$pid" 2>/dev/null || rc=$?
  kill "$wd" 2>/dev/null || true
  wait "$wd" 2>/dev/null || true
  return "$rc"
}

# detect_os: OS and the package manager; exits 3 on an unsupported system.
detect_os() {
  case "$(uname -s 2>/dev/null || echo unknown)" in
    Darwin) OS=macos; PKG=brew ;;
    Linux)
      OS=linux
      if command -v apt-get >/dev/null 2>&1; then PKG=apt
      elif command -v dnf >/dev/null 2>&1; then PKG=dnf
      else PKG=""; fi ;;
    *)
      error "unsupported operating system '$(uname -s 2>/dev/null)': supported are macOS and Linux (on Windows use WSL2 with a Linux distribution)"
      exit 3 ;;
  esac
}

# detect_engine: sets ENGINE, ENGINE_BIN, ENGINE_SHIM, ENGINE_VERSION; returns 1 when no engine answers.
detect_engine() {
  local candidates="docker podman" c server
  [ -z "${CONTAINER_ENGINE:-}" ] || candidates="$CONTAINER_ENGINE"
  ENGINE="" ENGINE_BIN="" ENGINE_SHIM=0 ENGINE_VERSION=""
  for c in $candidates; do
    command -v "$c" >/dev/null 2>&1 || continue
    if with_timeout "$PROBE_TIMEOUT" "$c" info >/dev/null 2>&1; then
      ENGINE_BIN="$c"
      break
    fi
  done
  [ -n "$ENGINE_BIN" ] || return 1
  ENGINE="$ENGINE_BIN"
  if [ "$ENGINE_BIN" = docker ]; then
    server="$(with_timeout "$PROBE_TIMEOUT" docker version --format '{{json .Server}}' 2>/dev/null || true)"
    case "$server" in *[Pp]odman*) ENGINE=podman; ENGINE_SHIM=1 ;; esac
  fi
  ENGINE_VERSION="$(with_timeout "$PROBE_TIMEOUT" "$ENGINE_BIN" version --format '{{.Server.Version}}' 2>/dev/null || true)"
  [ -n "$ENGINE_VERSION" ] ||
    ENGINE_VERSION="$(with_timeout "$PROBE_TIMEOUT" "$ENGINE_BIN" version --format '{{.Client.Version}}' 2>/dev/null || true)"
  return 0
}

# engine_info_field N: field N of one cached `<engine> info` call (1 memory bytes, 2 CPUs, 3 storage root).
ENGINE_INFO=""
engine_info_field() {
  [ -n "$ENGINE_BIN" ] || return 1
  if [ -z "$ENGINE_INFO" ]; then
    ENGINE_INFO="$(with_timeout "$PROBE_TIMEOUT" "$ENGINE_BIN" info --format '{{.MemTotal}} {{.NCPU}} {{.DockerRootDir}}' 2>/dev/null || true)"
    [ -n "$ENGINE_INFO" ] || return 1
  fi
  printf '%s\n' "$ENGINE_INFO" | awk -v n="$1" '{ print $n }'
}

engine_memory_bytes() { engine_info_field 1; }
engine_cpus() { engine_info_field 2; }
engine_root_dir() { engine_info_field 3; }

# compose_version_of CMD...: major.minor.patch reported by `CMD version`, empty when it does not answer.
compose_version_of() {
  local out
  out="$(with_timeout "$PROBE_TIMEOUT" "$@" version 2>/dev/null || true)"
  printf '%s\n' "$out" | sed -n 's/.*[^0-9.]\([0-9][0-9]*\.[0-9][0-9]*\(\.[0-9][0-9]*\)*\).*/\1/p' | head -n1
}

# detect_compose_cmd: sets COMPOSE (array) and COMPOSE_VERSION; returns 1 when no provider answers.
detect_compose_cmd() {
  COMPOSE=()
  COMPOSE_VERSION=""
  if [ -n "${COMPOSE_CMD:-}" ]; then
    read -r -a COMPOSE <<<"$COMPOSE_CMD"
    COMPOSE_VERSION="$(compose_version_of "${COMPOSE[@]}")"
    [ -n "$COMPOSE_VERSION" ] && return 0
    COMPOSE=()
    return 1
  fi
  local order
  case "$ENGINE_BIN" in
    podman) order="podman+compose docker-compose podman-compose" ;;
    *) order="docker+compose docker-compose podman+compose" ;;
  esac
  local c bin
  for c in $order; do
    bin="${c%%+*}"
    command -v "$bin" >/dev/null 2>&1 || continue
    case "$c" in
      *+*) COMPOSE=("$bin" "${c#*+}") ;;
      *) COMPOSE=("$bin") ;;
    esac
    COMPOSE_VERSION="$(compose_version_of "${COMPOSE[@]}")"
    [ -n "$COMPOSE_VERSION" ] && return 0
  done
  COMPOSE=()
  return 1
}

# major_of VERSION: first numeric component (`v24.4.1` gives 24).
major_of() {
  local v="$1"
  v="${v#v}"
  printf '%s\n' "${v%%[!0-9]*}" | sed 's/^$/0/'
}

# java_major_of VERSION: Java feature version (`25.0.4` gives 25, legacy `1.8.0_392` gives 8).
java_major_of() {
  case "$1" in
    1.*) major_of "${1#1.}" ;;
    *) major_of "$1" ;;
  esac
}

# version_ge A B: true when version A >= B (compares up to three numeric components).
version_ge() {
  local a b i x y
  a="${1#v}"; b="${2#v}"
  for i in 1 2 3; do
    x="$(printf '%s' "$a" | cut -d. -f"$i" | sed 's/[^0-9].*//')"
    y="$(printf '%s' "$b" | cut -d. -f"$i" | sed 's/[^0-9].*//')"
    x="${x:-0}"; y="${y:-0}"
    [ "$x" -gt "$y" ] && return 0
    [ "$x" -lt "$y" ] && return 1
  done
  return 0
}

# gib_of BYTES: integer GiB.
gib_of() { printf '%s\n' $(( ${1:-0} / 1073741824 )); }

# is_interactive: a terminal on stdin, unless DEV_ENV_NON_INTERACTIVE says otherwise (1 never, 0 always).
is_interactive() {
  case "${DEV_ENV_NON_INTERACTIVE:-}" in
    1) return 1 ;;
    0) return 0 ;;
  esac
  [ -t 0 ]
}

# read_answer VAR: one line of the answer to a prompt into VAR (empty at end of input); keeps the output on its own
# line when the answer did not come from a terminal (a terminal echoes the newline itself).
read_answer() {
  local _line=""
  read -r _line || _line=""
  [ -t 0 ] || printf '\n'
  eval "$1=\"\$_line\""
}
