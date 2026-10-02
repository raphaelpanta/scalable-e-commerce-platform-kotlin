# Quickstart: Publish the Repository Publicly

**Feature**: 003-github-public-repo | **Audience**: the maintainer (`raphaelpanta`)

Run every command from the repository root. Steps 1 and 2 are local and read-only. Step 3 is
outward-facing. Step 4 onward verifies the result. Target total time: under 30 minutes (SC-006).

## Prerequisites

- `gh` authenticated (`gh auth status` shows `raphaelpanta` with scopes `repo`, `workflow`, `read:org`).
- `git` user configured (`git config user.name`, `git config user.email`).
- `gitleaks` (the bootstrap script installs it with `brew install gitleaks` if it is absent; skipped in
  dry-run).
- All feature tasks T001 to the last pre-publication task are complete, so the community files, ignore
  rules, hook, scripts and the constitution clean-up exist in the working directory.

## 1. Run the offline test suite

```bash
scripts/tests/run-all.sh
```

Expected: silent apart from one summary line `PASS: N tests`, exit code 0. The suite uses a stub `gh`
and temporary repositories; it makes no call to GitHub. A failure prints only the failing assertion.

## 2. Dry-run the bootstrap script (read-only)

```bash
scripts/bootstrap-repo.sh --dry-run
```

Expected: preflight passes (signed in, owner `raphaelpanta`, name `scalable-e-commerce-platform-kotlin`
free, `gitleaks` present or noted as "would install"), then every mutating step is printed prefixed
`DRY-RUN` (init, hook path, staging, commit, `gh repo create`, push, settings, topics, vulnerability
reporting, branch protection) and nothing changes: no `.git` directory appears and `gh` is only called
for reads. Re-run it twice; the output is identical. If the repository name is already taken or you are
signed out, the script stops here with a clear message and nothing has been created (spec edge cases).
Review the printed owner, name, description, topics and visibility before continuing.

## 3. Perform the publication (OUTWARD-FACING, REQUIRES THE MAINTAINER'S CONFIRMATION)

This step creates a public repository under your GitHub account and pushes the whole history. A public
push is effectively irreversible: anything pushed can be copied immediately. Do not run it until you have
read the dry-run output and confirmed owner, name, visibility and content.

```bash
scripts/bootstrap-repo.sh --require-check verify   # omit --require-check until feature 002's verify workflow exists
```

The script asks you to type the repository name to confirm (or accept `--yes` if you have already
decided). It then: removes the constitution comment, initialises git on `main`, activates
`core.hooksPath`, unstages ignored paths, scans with gitleaks, makes one initial commit, creates the
public repository, pushes with `-u`, applies description, topics, secret scanning and push protection,
private vulnerability reporting, squash-only merging, Actions fork-PR hardening and branch protection,
and finally runs the verification script. Safe to re-run after an interruption: it skips what already
exists and never duplicates the initial commit. Pass `--admin-bypass` only if you are the sole maintainer
and accept that you can then push to `main`; the script logs the choice.

## 4. Run the verification script

```bash
scripts/verify-repo.sh
```

Expected: one `OK` line per check and a final `VERIFIED` line, exit code 0: public visibility seen
anonymously, default branch `main` matches local, anonymous clone tree equals local `HEAD` tree, no ignored
paths in the clone, licence `MIT`, topics and description present, secret scanning and push protection
enabled, private vulnerability reporting enabled, branch protection and squash-only flags as specified,
community profile at 100%. If the repository was accidentally created private, the visibility check fails
and names the remedy: `gh repo edit --visibility public --accept-visibility-change-consequences`, then
re-run the script.

## 5. Test that a seeded secret push is blocked

Use a scratch branch; the push never reaches the remote when the gate works.

```bash
git switch -c scratch/seeded-secret
# Assemble a fake token at runtime so no secret-shaped literal is stored in the repository history:
printf 'token=%s\n' "ghp_$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 36)" > seeded-secret.txt
git add seeded-secret.txt && git commit -q -m "test: seeded secret"
git push -u origin scratch/seeded-secret
```

Expected: the push is refused by the local pre-push hook, naming `seeded-secret.txt` and the line, with the
secret redacted. Optional server-side check: `git push --no-verify -u origin scratch/seeded-secret` should
be rejected by GitHub push protection when the generated value matches a supported token pattern; if
GitHub does not flag the synthetic value, treat that as inconclusive and rely on the hook result, which is
authoritative for SC-003 (`scripts/tests/test_pre_push_hook.sh` covers it offline). Clean up:

```bash
git switch main && git branch -D scratch/seeded-secret && rm -f seeded-secret.txt
```

## 6. Test that a direct push to `main` is rejected

```bash
git switch -c scratch/direct-push
git commit -q --allow-empty -m "test: direct push must be rejected"
git push origin HEAD:main
```

Expected: `remote: error: GH006: Protected branch update failed` (changes must be made through a pull
request, 1 approval and resolved conversations required). Clean up with
`git switch main && git branch -D scratch/direct-push`. Do not run this step if you used
`--admin-bypass`; with that flag the push would succeed, defeating the test.

## 7. Signed-out and second-machine check

In a private browser window, open `https://github.com/raphaelpanta/scalable-e-commerce-platform-kotlin`
and time how long it takes to find the README, licence, `CONTRIBUTING.md` and `SECURITY.md`. On a second
machine (or a fresh temporary directory), run:

```bash
git clone https://github.com/raphaelpanta/scalable-e-commerce-platform-kotlin.git && cd scalable-e-commerce-platform-kotlin
```

and, once feature 002 provides the build, the project's verify command.

## Expected Outcomes Mapped to Success Criteria

| Criterion | How it is shown | Expected outcome |
|-----------|-----------------|------------------|
| SC-001 signed-out visitor finds overview, licence, contribution guide, security policy in under 2 minutes | Step 7 private-window check; `verify-repo.sh` visibility and file checks | Everything reachable from the repository front page within 2 minutes |
| SC-002 zero ignored paths, zero secret findings, zero local-only files | Step 4 anonymous clone compared to ignore rules; gitleaks scan in step 3; GitHub secret scanning alerts empty | `VERIFIED`; zero findings |
| SC-003 100% of direct `main` pushes rejected, 100% of seeded-secret pushes blocked | Steps 5 and 6; `scripts/tests/test_pre_push_hook.sh` | Hook blocks the seeded push; GH006 rejection on direct push |
| SC-004 anonymous clone on a second machine passes the project verify command | Step 7 second-machine clone, then the verify command from feature 002 | Passes once the build exists; until then the clone-equals-local check in step 4 stands in |
| SC-005 licence name displayed and community checklist complete | Step 4 licence and community-profile checks; repository front page | Licence shown as MIT; community profile 100% |
| SC-006 whole procedure under 30 minutes | Steps 2 to 4 timed from a clean unversioned directory | Under 30 minutes following this document |

## Remediation if a Secret Is Ever Pushed

Rotate the exposed credential first, then rewrite the offending commit (for example with
`git filter-repo --replace-text`), force-push through a temporary branch-protection exception, and ask
GitHub support to purge cached views if the value was ever visible. `CONTRIBUTING.md` and `SECURITY.md`
carry the same steps.
