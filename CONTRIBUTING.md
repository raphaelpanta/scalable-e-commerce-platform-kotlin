# Contributing

Thank you for helping. This repository follows the rules in the
[constitution](.specify/memory/constitution.md); features are specified under [`specs/`](specs/).
Please also read the [code of conduct](CODE_OF_CONDUCT.md).

## Set up your clone

1. Install [gitleaks](https://github.com/gitleaks/gitleaks): `brew install gitleaks` (macOS) or see its
   documentation for other platforms.
2. Activate the versioned secret-scanning hook in your clone. Git does not apply it automatically:

   ```bash
   git config core.hooksPath .githooks
   ```

   The `pre-push` hook scans the commits you are about to push and refuses the push on any finding. It
   fails closed: without `gitleaks` it blocks the push. Never bypass it with `--no-verify`. GitHub also
   runs secret scanning with push protection on the server as a second layer.
3. Run the offline repository tests when you change anything under `scripts/`:
   `scripts/tests/run-all.sh`.

## Branch and pull-request flow

1. Branch from `main`: `git switch -c <short-description>`.
2. Keep changes focused and commit often; intermediate commits are squashed on merge.
3. Open a pull request against `main` and complete every item of the pull-request template.
4. `main` is protected: direct pushes and force pushes are rejected, history is linear, a review
   approval and resolved conversations are required, and required checks must pass.
5. Pull requests are merged by **squash merge only**. The pull-request title becomes the commit
   subject, so write it as a clear, imperative summary.

## Constitution gates

Every pull request is reviewed against the constitution. In particular: tests at every layer that applies,
the threat model for the change considered (Principle III), mutation-test threshold held with any
surviving mutants justified, no entries in the bypass log, and the Constitution Check in the relevant
`plan.md` passed. Deviations must be justified in the plan's Complexity Tracking table.

## Workflow safety (self-hosted runner)

Continuous integration may run on a self-hosted runner, and anyone can open a pull request against a
public repository. To keep untrusted code away from that runner:

- Do not use the `pull_request_target` trigger with a checkout of untrusted (fork) code.
- Trigger workflows only on `push` to protected branches and on `pull_request` from collaborators; fork
  pull requests wait for maintainer approval.
- Pin every third-party action to a full commit SHA, never to a tag or branch.
- Grant workflows the minimum `permissions:` (the repository default token is read-only).
- Never echo secrets and never store them in the repository; inject them at runtime.

## If you ever commit a secret

1. **Rotate or revoke the credential immediately**; assume it is compromised.
2. If it was not pushed, rewrite your local history (`git commit --amend`, or `git filter-repo
   --replace-text`) and push again.
3. If it was pushed, tell the maintainer privately (see [SECURITY.md](SECURITY.md)). The history will be
   rewritten with `git filter-repo`, force-pushed through a temporary branch-protection exception, and
   GitHub support asked to purge cached views.

## Licence of contributions

By contributing you agree that your contribution is licensed under the [MIT License](LICENSE) that covers
this project.
