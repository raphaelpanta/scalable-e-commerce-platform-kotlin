#!/usr/bin/env bash
# Ignore-rule test: the real .gitignore hides local/secret/build material and nothing that must be published.
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"

tmp="$(mk_tmp)"
git -C "$tmp" init -q -b main
cp "$PROJECT_ROOT/.gitignore" "$tmp/.gitignore"

ignored() { git -C "$tmp" check-ignore -q -- "$1"; }

test_case "local, build and secret paths are ignored"
for p in \
  .claude/.cache/state.json .claude/settings.local.json .tokensave/tokensave.db .rtk/filters.toml \
  build/classes/Main.class services/orders/build/libs/app.jar .gradle/8.10/cache.bin .kotlin/errors/e.log \
  node_modules/react/index.js frontend/node_modules/x/y.js .idea/workspace.xml module.iml .vscode/settings.json \
  .DS_Store src/.DS_Store .env .env.local server.pem tls/private.key debug.log scratch.tmp; do
  ignored "$p" || _fail "expected '$p' to be ignored"
done

test_case "published paths are not ignored"
for p in \
  .env.example specs/003-github-public-repo/spec.md .specify/memory/constitution.md .claude/settings.json \
  .claude/hooks/post-edit-check.sh .github/CODEOWNERS .githooks/pre-push .gitleaks.toml scripts/bootstrap-repo.sh \
  build-logic/build.gradle.kts gradle/libs.versions.toml gradle/wrapper/gradle-wrapper.jar \
  settings.gradle.kts README.md LICENSE CLAUDE.md; do
  ! ignored "$p" || _fail "expected '$p' NOT to be ignored"
done

finish_tests
