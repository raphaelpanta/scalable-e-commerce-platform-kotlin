#!/usr/bin/env bash
# Shellcheck over the repository's shell scripts: the same file list as the platform workflow's shellcheck step and
# `./gradlew -q scriptsCheck` (FR-029). Prints nothing on success. Without shellcheck on PATH it prints nothing and
# exits 0 (CI runs the pinned shellcheck image), unless --require is given.
#
# Usage: scripts/lint.sh [--require]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v shellcheck >/dev/null 2>&1; then
  if [ "${1:-}" = --require ]; then
    echo "ERROR: shellcheck is not installed (brew install shellcheck)" >&2
    exit 1
  fi
  exit 0
fi

files=()
for f in scripts/*.sh scripts/lib/*.sh scripts/lib/dev-env/*.sh scripts/tests/*.sh scripts/tests/lib/*.sh \
  scripts/tests/stubs/* platform/compose/scripts/*.sh platform/ci-runner/scripts/*.sh platform/perf/*.sh; do
  [ -f "$f" ] && files+=("$f")
done

exec shellcheck -x -P SCRIPTDIR "${files[@]}"
