# Continuous integration

`.github/workflows/verify.yml` runs the repository gate, `./gradlew -q verify` (see [build.md](build.md)),
so CI and a developer run the same command. It runs on every push to `main` and, as a reusable workflow
(`workflow_call`), on every pull request through `.github/workflows/pr-gate.yml`, the pull-request gate of
feature 001 ([harness.md](harness.md)), which adds the mutation threshold, the baseline ratchet, the
surviving-mutant check and the harness self-tests on top of it.

## Required status check

Branch protection (feature 003) must require exactly one status check on `main`: **`pr-gate`**, the final job
of `.github/workflows/pr-gate.yml`, which fails unless `verify`, `mutation` and `hook-tests` all succeeded. On a
pull request the verify run appears as `verify / verify` (caller job / called job) under the workflow `pr-gate`;
do not require it separately. On `main` the workflow `verify` runs on its own after every push.

## Triggers and safeguards

| Safeguard | How it is enforced |
| --- | --- |
| Triggers | `push` to `main` and `workflow_call` from `pr-gate.yml` (which triggers on `pull_request` only); no `pull_request_target`, no `workflow_run` |
| No fork code on the runner | the job runs only when `github.event_name == 'push'` or `github.event.pull_request.head.repo.full_name == github.repository`; set the repository setting "Require approval for all outside collaborators" as a second barrier |
| Least privilege | workflow `permissions: contents: read`, checkout with `persist-credentials: false`, no secrets used |
| Supply chain | every `uses:` is pinned to a full commit SHA with the release in a trailing comment; the Gradle wrapper jar is validated by `gradle/actions/setup-gradle` |
| One run per ref | `concurrency` group `verify-${{ github.event_name }}-${{ github.ref }}` with `cancel-in-progress: true` (distinct from the caller's `pr-gate-<ref>` group) |
| Bounded run | `timeout-minutes: 15` |
| Diagnosis | on failure the `verify-reports` artifact holds `**/build/reports/**`, `**/build/test-results/**` and the Pitest log for 7 days |
| Mutation hand-over | called with `mutation-reports: true` (pr-gate), a green run uploads `pitest-reports` (a tar of every `mutations.xml`, kept 1 day) for the pr-gate `mutation` job |

Gradle caches are written only from `main` (`cache-read-only` is true everywhere else).

## Runner requirements

The job uses `runs-on: [self-hosted, linux, ecommerce]`: a containerised self-hosted runner carrying the
labels `linux` (added automatically) and `ecommerce`. The decision and its security notes are in
`specs/004-ecommerce-platform-mvp/research.md` section 4. The runner needs:

- Docker API access (socket mounted into the runner container), for Testcontainers in the integration,
  contract and acceptance layers.
- Outbound network access to `services.gradle.org`, Maven Central, the Gradle Plugin Portal and the JDK
  download endpoints (the Foojay resolver provisions the JDK 25 toolchain if `setup-java` has not already
  supplied it), or pre-warmed caches.
- At least 4 CPU cores and 8 GB of memory (Gradle daemon 3 GB, Kotlin daemon 2 GB, Testcontainers).
- Ephemeral registration (just-in-time): each job should get a clean container. Registering such runners is
  part of the platform work in feature 004, not of this workflow. Until then the runner must be treated as
  disposable and must hold no credentials beyond its registration token.
- No secrets in its environment. `verify` needs none.

Because the repository is public, GitHub advises against self-hosted runners. If the mitigations above
cannot be met, switch `runs-on` to a GitHub-hosted label (`ubuntu-latest`, which has Docker) for pull
requests and keep the self-hosted runner for post-merge work only.

## Updating pinned actions

```bash
gh api repos/actions/checkout/commits/<tag> --jq .sha
```

Replace the SHA and the version comment together. The same applies to `actions/setup-java`,
`gradle/actions`, `actions/upload-artifact` and, in `pr-gate.yml`, `actions/download-artifact`.
