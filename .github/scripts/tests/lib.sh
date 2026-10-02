# shellcheck shell=bash
# Helpers for the pull-request gate script tests: a throw-away repository per test built from the fixtures
# (Pitest mutations.xml per module, a diff, PR bodies, a baseline), a runner and assertions.
# Source it: . "$(dirname "$0")/lib.sh"; call new_repo, run_script, assert..., then test_done.

T_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="$(cd "$T_DIR/.." && pwd)"
REPO_ROOT="$(cd "$SCRIPTS_DIR/../.." && pwd)"
FIX="$T_DIR/fixtures"
T_NAME="$(basename "${0:-test}")"
T_FAILS=0
T_TMPS=""
T_ROOT=""; T_OUT=""; RC=0
SRC_REL="src/main/kotlin/com/ecommerce/demo/domain/Price.kt"

_t_cleanup() { local d; for d in $T_TMPS; do rm -rf "$d"; done; }
trap _t_cleanup EXIT

# new_repo MODULE...: a fresh repository in $T_ROOT with services/demo/<MODULE> for each fixture module
# (domain-ok, domain-low, domain-empty): build.gradle.kts, the Kotlin source and the Pitest XML report placed
# where the Gradle plugin writes it. `build/` is git-ignored in the real repository, hence the copy here.
new_repo() {
  local base m d; base="$(mktemp -d "${TMPDIR:-/tmp}/prgatetest.XXXXXX")"
  base="$(cd "$base" && pwd -P)"
  T_TMPS="$T_TMPS $base"
  T_ROOT="$base/repo"; T_OUT="$base/out"
  mkdir -p "$T_ROOT/quality" "$T_OUT"
  echo '{}' >"$T_ROOT/quality/mutation-baseline.json"
  for m in "$@"; do
    d="$T_ROOT/services/demo/$m"
    mkdir -p "$d/build/reports/pitest" "$(dirname "$d/$SRC_REL")"
    printf 'plugins {\n    id("kotlin-domain")\n}\n' >"$d/build.gradle.kts"
    cp "$FIX/Price.kt" "$d/$SRC_REL"
    cp "$FIX/pitest/$m.xml" "$d/build/reports/pitest/mutations.xml"
  done
  unset PR_BODY PR_BASE_SHA GITHUB_EVENT_PATH GITHUB_STEP_SUMMARY HOOK_DRY_RUN CI GITHUB_ACTIONS
  export PR_GATE_ROOT="$T_ROOT"
}

# git_init: commit the current tree as `base` (branch main) and leave HEAD there.
git_init() {
  (cd "$T_ROOT" && git init -q -b main . && git -c user.name=t -c user.email=t@t add -A &&
    git -c user.name=t -c user.email=t@t commit -qm base && git tag base) >/dev/null 2>&1 ||
    _t_fail "git init failed"
}
git_commit() { (cd "$T_ROOT" && git -c user.name=t -c user.email=t@t commit -qam "$1") >/dev/null 2>&1; }

# run_script NAME ARGS...: runs .github/scripts/NAME; sets RC, leaves stdout/stderr in $T_OUT.
run_script() {
  local s="$1"; shift
  "$SCRIPTS_DIR/$s" "$@" >"$T_OUT/stdout" 2>"$T_OUT/stderr" </dev/null
  RC=$?
}

_t_file() { case "$1" in stdout|stderr) echo "$T_OUT/$1" ;; *) echo "$1" ;; esac; }
_t_fail() { T_FAILS=$((T_FAILS + 1)); echo "  ASSERT FAILED ($T_NAME): $*"; }
assert_exit() { [ "$RC" -eq "$1" ] || _t_fail "exit code $RC, expected $1${2:+ ($2)}: $(head -c 400 "$T_OUT/stdout" "$T_OUT/stderr" 2>/dev/null)"; }
assert_fails() { [ "$RC" -ne 0 ] || _t_fail "expected a failure${1:+ ($1)}"; }
assert_silent() {
  [ ! -s "$T_OUT/stdout" ] && [ ! -s "$T_OUT/stderr" ] ||
    _t_fail "expected no output: $(head -c 400 "$T_OUT/stdout" "$T_OUT/stderr")"
}
assert_contains() { grep -qF -- "$2" "$(_t_file "$1")" || _t_fail "$1 does not contain: $2 (got: $(head -c 400 "$(_t_file "$1")"))"; }
assert_not_contains() { ! grep -qF -- "$2" "$(_t_file "$1")" || _t_fail "$1 unexpectedly contains: $2"; }
assert_matches() { grep -qE -- "$2" "$(_t_file "$1")" || _t_fail "$1 does not match: $2"; }

test_done() {
  if [ "$T_FAILS" -ne 0 ]; then echo "$T_NAME: $T_FAILS assertion(s) failed"; exit 1; fi
  exit 0
}
