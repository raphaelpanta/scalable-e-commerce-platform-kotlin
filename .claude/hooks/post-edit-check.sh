#!/usr/bin/env bash
# PostToolUse hook (Write|Edit): lint + targeted tests for the file just edited.
# Constitution VIII: quiet on success; on failure print a short report and exit 2 so the agent
# sees the problem and fixes it before moving on.
#   HOOK_DRY_RUN=1        print the commands instead of running them
#   HOOK_BYPASS=1         skip the checks once; one line is appended to .claude/.cache/bypass.log
#   HOOK_BUDGET_SECONDS   total time budget for the checks of one edit (default 300)
# Report format and caps: see lib/common.sh (report_failure, HOOK_TAIL_LINES_FILE=60).
set -uo pipefail

# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"
ROOT="$(hook_root)"
INPUT="$(cat)"
FILE="$(printf '%s' "$INPUT" | jq -r '.tool_response.filePath // .tool_input.file_path // empty' 2>/dev/null)"
[ -n "$FILE" ] && [ -f "$FILE" ] || exit 0
case "$FILE" in "$ROOT"/*) ;; *) exit 0 ;; esac
REL="${FILE#"$ROOT"/}"
case "$REL" in
  build/*|*/build/*|node_modules/*|*/node_modules/*|.gradle/*|*/.gradle/*|.claude/*|.specify/*|dist/*|*/dist/*) exit 0 ;;
esac

# any_glob PATTERN... : true if any pattern matches an existing path
any_glob() { local f; for f in "$@"; do [ -e "$f" ] && return 0; done; return 1; }

LOG="$(mktemp 2>/dev/null || echo /dev/null)"
DRY="${HOOK_DRY_RUN:-}"
RAN=""; STATUS=pass
deadline_init 300
on_exit() {
  [ "$LOG" = /dev/null ] || rm -f "$LOG"
  [ -n "$RAN" ] && [ -z "$DRY" ] && log_timing post-edit "$(deadline_elapsed)" "$STATUS"
}
trap on_exit EXIT

[ -n "$DRY" ] || { hook_bypass post-edit "$REL" && exit 0; }

# run_in DIR CHECK CMD... : run quietly within the shared budget; on failure (or timeout) report the
# check name, the command and a bounded log tail, then block with exit 2.
run_in() {
  local dir="$1" check="$2" rem rc=124; shift 2
  if [ -n "$DRY" ]; then echo "DRY [$dir]: $*"; return 0; fi
  RAN=1
  rem="$(deadline_remaining)"
  [ "$rem" -gt 0 ] && { (cd "$dir" && run_budgeted "$rem" "$@") >"$LOG" 2>&1; rc=$?; }
  [ "$rc" -eq 0 ] && return 0
  STATUS=fail
  if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then check="$check: TIMEOUT after ${GATE_BUDGET}s"; STATUS=timeout; fi
  report_failure post-edit "$check" "$dir" "$LOG" "$HOOK_TAIL_LINES_FILE" "$@" >&2
  exit 2
}

gradle_cmd() {
  if [ -x "$ROOT/gradlew" ]; then echo "$ROOT/gradlew"
  elif command -v gradle >/dev/null 2>&1; then echo gradle
  fi
}

# gradle_path MODULE_DIR : ":a:b" for ROOT/a/b, "" for the root project
gradle_path() {
  local rel="${1#"$ROOT"}"; rel="${rel#/}"
  [ -z "$rel" ] && { echo ""; return; }
  echo ":${rel//\//:}"
}

build_mentions() { # build_mentions MODULE_DIR WORD : is WORD configured somewhere in the build?
  grep -rqs -- "$2" "$1/build.gradle.kts" "$ROOT/build.gradle.kts" "$ROOT/build-logic" "$ROOT/gradle/libs.versions.toml" 2>/dev/null
}

check_kotlin() {
  local gradle; gradle="$(gradle_cmd)"; [ -n "$gradle" ] || exit 0
  local mod; mod="$(nearest_up "$(dirname "$FILE")" build.gradle.kts)" || exit 0
  local p; p="$(gradle_path "$mod")"
  local tasks=()
  build_mentions "$mod" ktlint && tasks+=("$p:ktlintCheck")
  build_mentions "$mod" detekt && tasks+=("$p:detekt")

  # Targeted tests (D2): the edited test class itself, or every test class named <Base>*.kt
  # (Kotest *Spec / *Test / *PropertyTest, any src/*[tT]est* source set), at most 20. When nothing
  # covers the file the gate falls back to the style tasks only.
  local base; base="$(basename "$FILE" .kt)"
  local test_args=() t
  case "$REL" in
    */src/*[tT]est*/*)
      test_args+=(--tests "*.$base") ;;
    *)
      while IFS= read -r t; do
        [ -n "$t" ] && test_args+=(--tests "*.$(basename "$t" .kt)")
      done < <(find "$mod/src" -path '*/src/*[tT]est*/*' -name "${base}*.kt" 2>/dev/null | sort | head -20)
      ;;
  esac
  [ ${#test_args[@]} -gt 0 ] && tasks+=("$p:test" "${test_args[@]}")

  [ ${#tasks[@]} -gt 0 ] || exit 0
  run_in "$ROOT" gradle "$gradle" -q --console=plain "${tasks[@]}"
}

check_gradle_script() {
  local gradle; gradle="$(gradle_cmd)"; [ -n "$gradle" ] || exit 0
  # Compiling the build scripts is the cheapest validation of a *.gradle.kts change.
  run_in "$ROOT" gradle:help "$gradle" -q --console=plain help
}

check_typescript() {
  local pkg; pkg="$(nearest_up "$(dirname "$FILE")" package.json)" || exit 0
  export CI=1  # keeps vitest/jest out of watch mode (stdin is closed by run_budgeted as well)
  if any_glob "$pkg"/eslint.config.* "$pkg"/.eslintrc*; then
    run_in "$pkg" eslint npx --no-install eslint --max-warnings=0 "$FILE"
  fi
  if [ -f "$pkg/tsconfig.json" ]; then
    run_in "$pkg" tsc npx --no-install tsc --noEmit -p "$pkg"
  fi
  # Prettier only when the package or the repository configures it. Stryker is not part of this gate.
  if any_glob "$pkg"/.prettierrc* "$pkg"/prettier.config.* "$ROOT"/.prettierrc* "$ROOT"/prettier.config.*; then
    run_in "$pkg" prettier npx --no-install prettier --check "$FILE"
  fi
  if any_glob "$pkg"/vitest.config.* || grep -qs '"vitest"' "$pkg/package.json"; then
    run_in "$pkg" vitest npx --no-install vitest related "$FILE" --run --passWithNoTests
  fi
}

case "$FILE" in
  *.gradle.kts)                     check_gradle_script ;;
  *.kt|*.kts)                       check_kotlin ;;
  *.ts|*.tsx|*.mts|*.cts)           check_typescript ;;
  *)                                exit 0 ;;
esac
exit 0
