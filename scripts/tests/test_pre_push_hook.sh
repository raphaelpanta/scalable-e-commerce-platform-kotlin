#!/usr/bin/env bash
# Pre-push hook test: a runtime-generated GitHub-token-shaped secret is blocked; clean pushes pass;
# a new branch scans full history; a PATH without gitleaks fails closed. No secret literal is stored here.
set -uo pipefail
source "$(dirname "$0")/lib/assert.sh"
git_identity

command -v gitleaks >/dev/null 2>&1 || { echo "gitleaks must be installed to run this test (brew install gitleaks)" >&2; exit 1; }

tmp="$(mk_tmp)"
remote="$tmp/remote.git"
work="$tmp/work"
git init -q --bare -b main "$remote"
git init -q -b main "$work"
cp -R "$PROJECT_ROOT/.githooks" "$PROJECT_ROOT/.gitleaks.toml" "$work/"
git -C "$work" config core.hooksPath .githooks
git -C "$work" remote add origin "$remote"

# Assembled at runtime so the repository never contains a secret-shaped literal.
fake_token() { printf 'ghp_%s' "$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 36)"; }

commit_file() { # file content message
  printf '%s\n' "$2" >"$work/$1"
  git -C "$work" add "$1"
  git -C "$work" commit -q -m "$3"
}

test_case "hook is executable"
[ -x "$work/.githooks/pre-push" ] || _fail "pre-push is not executable"

test_case "a clean push passes"
commit_file README.txt "hello" "clean commit"
assert_exit_code 0 git -C "$work" push -q origin main

test_case "a seeded secret is blocked, names file and line, and is redacted"
secret="$(fake_token)"
commit_file seeded.txt "token=$secret" "seeded secret"
head_before="$(git -C "$remote" rev-parse main)"
assert_nonzero git -C "$work" push -q origin main
assert_contains "$LAST_OUT" "seeded.txt" "output names the file"
assert_contains "$LAST_OUT" "1" "output carries a line number"
assert_contains "$LAST_OUT" "Line" "output labels the line"
assert_not_contains "$LAST_OUT" "$secret" "secret value must be redacted"
assert_eq "$head_before" "$(git -C "$remote" rev-parse main)" "remote unchanged after blocked push"

test_case "a brand-new branch scans the full history"
# Put the secret on the remote main bypassing the hook, then push a NEW branch: the range-based scan
# would see nothing new, the full-history scan must still block.
git -C "$work" push -q --no-verify origin main
commit_file after.txt "clean" "clean follow-up"
git -C "$work" branch newbranch
assert_nonzero git -C "$work" push -q origin newbranch
assert_contains "$LAST_OUT" "seeded.txt" "full history scanned for the new branch"

test_case "without gitleaks on PATH the hook fails closed with install instructions"
nogl="/usr/bin:/bin"
if PATH="$nogl" command -v gitleaks >/dev/null 2>&1; then
  _fail "cannot build a PATH without gitleaks on this machine"
else
  LAST_RC=0
  LAST_OUT="$(cd "$work" && PATH="$nogl" /usr/bin/env bash .githooks/pre-push origin "$remote" </dev/null 2>&1)" || LAST_RC=$?
  [ "$LAST_RC" != 0 ] || _fail "hook passed without gitleaks"
  assert_contains "$LAST_OUT" "brew install gitleaks" "install instructions"
fi

finish_tests
