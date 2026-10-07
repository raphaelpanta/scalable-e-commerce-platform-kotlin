#!/usr/bin/env bash
# Stop hook: full quality gate at the end of a task (Constitution VIII / Quality Gate 2).
#  - Gradle `verify` (feature 002's root lifecycle task: toolchain and version-literal checks, build-logic
#    tests, every module's ktlint/detekt/test layers/architecture rules/full Pitest, frontend/ lint and test);
#    only when the build has no `verify` task, incremental Pitest on the changed domain/application classes;
#    and, per frontend package, lint/test
#    (+ Stryker incremental when a stryker.config.* exists: frontend/stryker.config.json since feature 005).
#    The root package frontend/package.json is left to `verify`, which already runs its lint and test scripts.
#  - Skipped when there is no build yet, or when nothing relevant changed since the last green run
#    (marker .claude/.cache/last-full-check, replaced only after every check passed).
#  - A Gradle "Task '...' not found" (no `verify`/`pitest` task yet) is a silent skip, not a failure.
#  - Quiet on success; on failure prints one consolidated report (bounded tails) and exits 2 so the agent
#    fixes it. If this hook already blocked once (stop_hook_active) it reports through a JSON
#    systemMessage with exit 0 instead, so it can never trap the agent in a loop.
#   HOOK_DRY_RUN=1        print the commands instead of running them
#   HOOK_BYPASS=1         skip the gate once; one line is appended to .claude/.cache/bypass.log
#   HOOK_BUDGET_SECONDS   total time budget of the run, shared by all checks (default 1200)
set -uo pipefail

# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"
ROOT="$(hook_root)"
INPUT="$(cat)"
ACTIVE="$(printf '%s' "$INPUT" | jq -r '.stop_hook_active // false' 2>/dev/null)"
DRY="${HOOK_DRY_RUN:-}"
CACHE="$(cache_dir)"
MARK="$CACHE/last-full-check"
STAMP="$MARK.new"

HAS_GRADLE=""; { [ -f "$ROOT/settings.gradle.kts" ] || [ -f "$ROOT/build.gradle.kts" ]; } && HAS_GRADLE=1
FE_PKGS="$(find "$ROOT" -maxdepth 3 -name package.json \
  -not -path '*/node_modules/*' -not -path '*/build/*' -not -path '*/.claude/*' 2>/dev/null)"
[ -n "$HAS_GRADLE" ] || [ -n "$FE_PKGS" ] || exit 0

deadline_init 1200

if [ -z "$DRY" ]; then
  # Nothing relevant changed since the last green run -> nothing to do (fast path, one find ... -quit).
  if [ -f "$MARK" ] && [ -z "$(changed_since_marker "$MARK" --any)" ]; then
    log_timing stop "$(deadline_elapsed)" skip
    exit 0
  fi
  hook_bypass stop - && exit 0
  mkdir -p "$CACHE" 2>/dev/null && touch "$STAMP" 2>/dev/null   # start stamp: becomes the marker if all is green
fi

# any_glob PATTERN... : true if any pattern matches an existing path
any_glob() { local f; for f in "$@"; do [ -e "$f" ] && return 0; done; return 1; }

TMP="$(mktemp -d 2>/dev/null || echo "")"
cleanup() { [ -z "$TMP" ] || rm -rf "$TMP"; [ -z "$DRY" ] && rm -f "$STAMP"; }
trap cleanup EXIT

FAILED=0
REPORT=""
RAN=""   # set by run_in: 1 when the last command really ran (or would run, in a dry run); empty when skipped
# run_in DIR LABEL CMD... : run within the shared deadline; failures are collected into REPORT.
# With TOLERATE_MISSING_TASK=1 a Gradle "Task '...' not found" counts as skipped, not failed.
run_in() {
  local dir="$1" label="$2" rem rc=124 log="${TMP:-/dev/null}/run.log"; shift 2
  RAN=1
  if [ -n "$DRY" ]; then echo "DRY [$label] (${dir#"$ROOT"}): $*"; return 0; fi
  rem="$(deadline_remaining)"
  if [ "$rem" -le 0 ]; then
    FAILED=1
    REPORT+=$'\n'"stop check FAILED: $label: not run: time budget exhausted (${GATE_BUDGET}s)"$'\n'
    return 0
  fi
  (cd "$dir" && run_budgeted "$rem" "$@") >"$log" 2>&1; rc=$?
  if [ "$rc" -eq 0 ]; then return 0; fi
  if [ "$rc" -ne 124 ] && [ "$rc" -ne 137 ] && [ "${TOLERATE_MISSING_TASK:-}" = 1 ] && log_has_missing_task "$log"; then
    RAN=""; return 0   # skipped: no such task yet
  fi
  FAILED=1
  if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then label="$label: TIMEOUT after ${GATE_BUDGET}s"; fi
  REPORT+=$'\n'"$(report_failure stop "$label" "$dir" "$log" "$HOOK_TAIL_LINES_COMPLETE" "$@")"$'\n'
}

gradle_cmd() {
  if [ -x "$ROOT/gradlew" ]; then echo "$ROOT/gradlew"
  elif command -v gradle >/dev/null 2>&1; then echo gradle
  fi
}

# gradle_path MODULE_DIR : ":a:b" for ROOT/a/b
gradle_path() {
  local rel="${1#"$ROOT"}"; rel="${rel#/}"
  [ -z "$rel" ] && { echo ""; return; }
  echo ":${rel//\//:}"
}

# Incremental mutation step (D5), the fallback for a build without `verify`. Changed src/main .kt files of modules named domain/application are
# mapped to class globs (<package>.<File>*) and Pitest runs per module with
# -Pharness.mutation.classes=<globs>, only in modules that actually have a `pitest` task (probed once
# per run with `gradle tasks --all`; in a dry run the module build file is searched for "pitest").
# Not changed -> not run. A "Task 'pitest' not found" answer is a skip, never a failure.
mutation_step() {
  local gradle="$1" changed f mod base pkg prefix modsfile="${TMP:-/tmp}/mutation.map"
  changed="$(changed_since_marker "${DRY:+/nonexistent}${DRY:-$MARK}")"
  : >"$modsfile"
  while IFS= read -r f; do
    case "$f" in */src/main/*.kt) ;; *) continue ;; esac
    case "${f#"$ROOT"/}" in build-logic/*|*/src/*/resources/*) continue ;; esac   # build tooling and test fixtures
    mod="$(nearest_up "$(dirname "$f")" build.gradle.kts)" || continue
    case "$(basename "$mod")" in domain|application) ;; *) continue ;; esac
    base="$(basename "$f" .kt)"
    pkg="$(sed -n 's/^package[[:space:]][[:space:]]*\([A-Za-z0-9_.`]*\).*/\1/p' "$f" 2>/dev/null | head -n 1 | tr -d '`')"
    prefix=""; [ -n "$pkg" ] && prefix="$pkg."
    printf '%s\t%s*\n' "$mod" "$prefix$base" >>"$modsfile"
  done <<<"$changed"
  [ -s "$modsfile" ] || return 0

  local tasks_out="" tasks_state=""   # cached per run
  local m globs p
  for m in $(cut -f1 "$modsfile" | sort -u); do
    p="$(gradle_path "$m")"
    if [ -n "$DRY" ]; then
      grep -qs pitest "$m/build.gradle.kts" || continue
    else
      if [ -z "$tasks_state" ]; then
        tasks_state=probed
        local rem; rem="$(deadline_remaining)"
        if [ "$rem" -gt 0 ]; then
          tasks_out="$( (cd "$ROOT" && run_budgeted "$rem" "$gradle" -q --console=plain tasks --all) 2>/dev/null)" || tasks_out=""
        fi
      fi
      printf '%s\n' "$tasks_out" | grep -Eq "(^|[[:space:]:])${p#:}:pitest([[:space:]]|\$)" || continue
    fi
    globs="$(grep "^$m	" "$modsfile" | cut -f2 | sort -u | paste -sd, -)"
    TOLERATE_MISSING_TASK=1 run_in "$ROOT" "gradle:pitest" "$gradle" -q --console=plain "$p:pitest" "-Pharness.mutation.classes=$globs"
  done
}

VERIFY_RAN=""
if [ -n "$HAS_GRADLE" ]; then
  gradle="$(gradle_cmd)"
  if [ -n "$gradle" ]; then
    TOLERATE_MISSING_TASK=1 run_in "$ROOT" gradle:verify "$gradle" -q --console=plain verify
    VERIFY_RAN="$RAN"
    # `verify` already mutation-tests every domain/application module in full (feature 002 makes `check`
    # depend on `pitest`), which covers the changed code; the narrowed run is the fallback without `verify`.
    [ -n "$VERIFY_RAN" ] || mutation_step "$gradle"
  fi
fi

export CI=1  # keeps vitest/jest/stryker out of watch/interactive mode (stdin is closed by run_budgeted)
while IFS= read -r pkgjson; do
  [ -n "$pkgjson" ] || continue
  pkg="$(dirname "$pkgjson")"
  if [ -f "$pkg/pnpm-lock.yaml" ]; then pm=pnpm; elif [ -f "$pkg/yarn.lock" ]; then pm=yarn; else pm=npm; fi
  for script in lint test; do
    # `gradlew verify` runs frontend/package.json's lint and test itself (feature 002 frontendCheck)
    [ -n "$VERIFY_RAN" ] && [ "$pkg" = "$ROOT/frontend" ] && continue
    if jq -e ".scripts[\"$script\"]" "$pkgjson" >/dev/null 2>&1; then
      run_in "$pkg" "${pkg##*/}:$script" "$pm" run -s "$script"
    fi
  done
  # Stryker (incremental) runs only when a stryker.config.* is present: the storefront's frontend/stryker.config.json.
  if any_glob "$pkg"/stryker.config.* "$pkg"/stryker.conf.*; then
    run_in "$pkg" "${pkg##*/}:stryker" npx --no-install stryker run --incremental
  fi
done <<<"$FE_PKGS"

if [ "$FAILED" -eq 0 ]; then
  if [ -z "$DRY" ]; then
    mv -f "$STAMP" "$MARK" 2>/dev/null   # atomic refresh, dated at the start of this run
    log_timing stop "$(deadline_elapsed)" pass
  fi
  exit 0
fi

log_timing stop "$(deadline_elapsed)" fail
if [ "$ACTIVE" = "true" ]; then
  # Already blocked once this turn; report without blocking to avoid a loop.
  jq -n --arg msg "Full quality check still failing:$REPORT" '{systemMessage: $msg}'
  exit 0
fi
printf 'Full quality check FAILED. Fix before finishing:%s\n' "$REPORT" >&2
exit 2
