# Continuous integration

`.github/workflows/verify.yml` runs the repository gate, `./gradlew -q verify` (see [build.md](build.md)),
so CI and a developer run the same command. It runs on every push to `main` and, as a reusable workflow
(`workflow_call`), on every pull request through `.github/workflows/pr-gate.yml`, the pull-request gate of
feature 001 ([harness.md](harness.md)), which adds the mutation threshold, the baseline ratchet, the
surviving-mutant check and the harness self-tests on top of it.

## Required status check

Branch protection (feature 003) requires **`pr-gate`** on `main`, the final job
of `.github/workflows/pr-gate.yml`, which fails unless `verify`, `mutation` and `hook-tests` all succeeded. On a
pull request the verify run appears as `verify / verify` (caller job / called job) under the workflow `pr-gate`;
do not require it separately. On `main` the workflow `verify` runs on its own after every push. Feature 004 adds the
second required check, **`services-aggregate`** (`.github/workflows/required-checks.yml`), which stands for the
path-filtered service and platform checks of the pull request (see "Required status checks and merge policy" below).

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
- Ephemeral registration (just-in-time): each job should get a clean container. The runner stack of feature 004
  (`platform/ci-runner/`, see "Feature 004" below) registers such runners; the runner must be treated as
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

## Feature 004: per-service pipelines, platform pipeline, registry

Feature 004 (user story 9, FR-028, SC-009) adds one pipeline per deployable, so a change to one service builds,
tests, scans and publishes only that service, and one pipeline for the platform (Compose, observability, acceptance).
Runner, private registry and Pact Broker live in `platform/ci-runner/` (setup in its [README](../platform/ci-runner/README.md)).

### Workflow map

| Workflow | Triggers | Paths (push to `main` and `pull_request` alike) | Check names |
| --- | --- | --- | --- |
| `gateway.yml`, `identity.yml`, `catalog.yml`, `cart.yml`, `order.yml`, `payment.yml`, `notification.yml` | push to `main`, pull request | `services/<ctx>/**`, `libs/**`, `build-logic/**`, `gradle/**`, `contracts/**`, `platform/docker/**`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `config/**`, `.github/workflows/service-ci.yml`, `.github/workflows/<ctx>.yml` | `service-ci / <ctx>` (per-service aggregate, informational: required through `services-aggregate`), `service-ci / gate (<ctx>)`, `service-ci / image (<ctx>)` |
| `service-ci.yml` | `workflow_call` only | (called by the seven workflows above with `service` and `publish-image`) | |
| `platform.yml` | pull request (filtered), every push to `main` except documentation-only ones, nightly (02:17 UTC), manual | pull request only: `platform/**`, `acceptance/**`, `contracts/**`, `.github/workflows/platform.yml`; the push trigger uses `paths-ignore` (`docs/**`, `specs/**`, `**.md`, `.specify/**`, `.claude/**`) so a service change merged to `main` is exercised against the whole stack | `platform`, `acceptance-slow` (not on pull requests) |
| `pr-gate.yml` / `verify.yml` | every pull request / push to `main` | none (feature 001) | `pr-gate`, `verify / verify` |
| `required-checks.yml` | every pull request | none (path-neutral on purpose) | `services-aggregate` (the one to require beside `pr-gate`) |

`<ctx>` is one of `gateway`, `identity`, `catalog`, `cart`, `order`, `payment`, `notification`. A check name is
`<caller job> / <called job>`: the caller job of every service workflow is `service-ci`, the aggregate job of
`service-ci.yml` is named after the service. The root build files (`settings.gradle.kts`, `build.gradle.kts`,
`gradle.properties`) and `config/**` are in the filters in addition to the paths of the task list, because they change
every service's build. Which workflows a change triggers can be simulated offline:

```bash
.github/scripts/path-filter-check.sh services/cart/domain/src/main/kotlin/Foo.kt     # cart.yml
git diff --name-only origin/main...HEAD | .github/scripts/path-filter-check.sh -     # same, for a branch
.github/scripts/tests/run-all.sh                                                    # includes test-path-filter-check.sh
```

### Per-service pipeline (`service-ci.yml`)

| Job | Step | Tool | Fails when |
| --- | --- | --- | --- |
| `gate` | Gradle `check` of the service modules (`:services:<ctx>:domain`, `:application`, `:infrastructure`; `:services:gateway`) | `./gradlew -q`: ktlint, detekt, unit, integration, contract, acceptance and architecture layers, Pitest (80 % threshold) | any layer, rule or the mutation threshold fails |
| | Publish consumer pacts | `pactfoundation/pact-cli` `publish build/pacts/<ctx>-*.json build/pacts/platform-probe-<ctx>.json --consumer-app-version <sha> --branch <branch> --build-url <run>` | the broker rejects the pacts; skipped without `PACT_BROKER_URL` or without pacts |
| | Provider verification | `./gradlew -q <module>:contractVerify` with `PACT_BROKER_URL`, credentials, `PACT_PUBLISH_RESULTS=true`, `PACT_PROVIDER_BRANCH` (and `PACT_URL`/`PACT_CONSUMER` in a webhook-dispatched run): `<Ctx>BrokerVerificationTest` verifies the broker's pacts and publishes the results (`pact.provider.version` = commit SHA) | a pact is not honoured; the output names the consumer and the interaction |
| | `can-i-deploy` | `pact-broker can-i-deploy --pacticipant <ctx> --version <sha> [--to-environment $PACT_ENVIRONMENT]`, retried every 10 s while verification results are unknown (`PACT_CAN_I_DEPLOY_RETRIES`, default 24) | an integrated version is incompatible; disabled by the variable `PACT_CAN_I_DEPLOY=false` |
| | Dependency scan | `gradle ... dependencies --configuration runtimeClasspath` converted by `.github/scripts/gradle-deps-to-lockfile.sh` to a `gradle.lockfile`, scanned by `osv-scanner` | a dependency has a known vulnerability; `DEPENDENCY_SCAN_ENFORCE=false` downgrades it to an annotation |
| `jar` | Boot jar | `./gradlew -q <module>:bootJar`, artifact `jar-<ctx>` (1 day) | the service does not compile or package |
| `image` | Build | `docker build -f platform/docker/Dockerfile --build-arg SERVICE_MODULE=<module> --build-arg APP_JAR=ci-jar/app.jar .` with `BUILDAH_FORMAT=docker`; the build stage copies the jar of the `jar` job instead of compiling | the build fails |
| | Image scan | `aquasec/trivy image --severity CRITICAL --ignore-unfixed --exit-code 1` | a CRITICAL vulnerability with a fix is in the image |
| | SBOM | `anchore/syft` CycloneDX JSON, artifact `sbom-<ctx>` (30 days) | |
| | Start and health | `.github/scripts/image-health.sh <ctx> <image>`: run the image (with a `postgres:18-alpine` sidecar except for the gateway), wait up to 90 s for the readiness group `/actuator/health/readiness` to report UP, stop everything | the container exits or does not report UP in 90 s; the last 80 log lines are printed |
| | Hand-over (push to `main` only) | `docker save` to the artifact `image-<ctx>` (1 day) | |
| `publish` (push to `main` only) | Push | `docker login --password-stdin`, `docker push <REGISTRY_HOST>/<ctx>:<sha>` and `:<branch>`; runs only when `gate` and `image` both succeeded | the registry rejects the push; skipped with a warning when `REGISTRY_HOST` or the secrets are missing |
| `<ctx>` | Aggregate | shell | `gate` or `image` did not succeed, or `publish` failed (a skipped `publish` is fine; a skipped `gate` or `image` counts as failure) |

`gate`, `jar`, `image` and `publish` each have `timeout-minutes: 15` and a concurrency group per service and ref
(`service-ci-<ctx>-<event>-<ref>`; pull-request runs are cancelled by a newer push, `main` runs never are). SC-009 asks for
build, test and publication of a single service within 15 minutes; see "Parallel jobs and the jar" and "Measuring the
end-to-end time" below. Pull requests build, scan and start the image but never push it.
On a failed `gate` the artifact `reports-<ctx>` (a tar of `**/build/reports`, `**/build/test-results` and the Pitest
log) is kept for 7 days; `dependencies-<ctx>` holds the dependency list and lockfile.

#### Parallel jobs and the jar (T150)

`gate` (checks, pacts, dependency scan) and `jar` (compile and package) have no dependency on each other and start together;
`image` follows `jar` only, so the image build, the Trivy scan, the SBOM and the start-and-health check overlap with the
Gradle `check` instead of waiting for it. `publish` needs both `gate` and `image`: nothing is pushed unless the gate passed,
and the image that is pushed is the one that was scanned and started (it travels as the artifact `image-<ctx>`, loaded with
`docker load`, so it does not matter which runner or engine the job lands on). The aggregate `service-ci / <ctx>` needs all
of them. Parallelism needs two free runner slots: with one runner replica the jobs queue one after the other, as before.

The Docker build no longer compiles when the pipeline hands it the jar: `platform/docker/Dockerfile` has the optional build
argument `APP_JAR` (default empty), the path of a pre-built boot jar inside the build context. When it is set, the build
stage copies that file; when it is empty (Compose, `./gradlew :services:<ctx>:infrastructure:dockerImage`, a developer's
`docker build`) it runs `bootJar` with Gradle exactly as before. The jar job and the gate each compile once on their own
runner, in parallel, instead of the image build compiling a second time behind the gate.

#### Start-and-health check (T149, US9/AC3)

`image` runs `.github/scripts/image-health.sh <ctx> <image>` against the image it built. Approach, chosen to be the simplest
check that does not need the platform:

- A service reports UP when its own PostgreSQL answers (the `CheckServiceHealth` use case; Kafka, the JWKS endpoint and the
  observability stack are not part of the result). The script therefore starts one throw-away `postgres:18-alpine` (the
  tag Compose uses) on a private network with random credentials, and starts the image with `<CTX>_DB_HOST`,
  `<CTX>_DB_USER`, `<CTX>_DB_PASSWORD`, a random `INTERNAL_API_TOKEN` and `SEED=false` (docs/service-conventions.md section 2).
  Flyway migrates the empty database. Kafka and the OTLP collector keep their unreachable `localhost` defaults;
  `SPRING_KAFKA_ADMIN_AUTO_CREATE=false` keeps the topic admin from waiting for a broker.
- The gateway has no database: it is started alone with its defaults.
- The probe runs inside the container (`docker exec ... bash` with `/dev/tcp` to `127.0.0.1:8081`, the same as the image
  HEALTHCHECK), so no port is published and the runner's host network, a remote engine or Podman all behave the same. It
  polls the readiness group `/actuator/health/readiness` every 2 seconds for up to 90 seconds for `"status":"UP"`.
- On failure the step prints the last 80 log lines of the service (and 20 of the database) and exits non-zero; an exit of
  the container ends the wait early. A shell `trap` always removes the containers, their volumes and the network.

Rehearse it locally against an image you built: `.github/scripts/image-health.sh cart ecommerce-platform/cart:local`
(`HEALTH_TIMEOUT=30` shortens the wait). It was run that way against the Compose images of the gateway, catalog and cart
(UP after 5 to 7 seconds). The service images are pinned by the registry digest only for the base images in the
Dockerfile; `postgres:18-alpine` is a tag, like in Compose (override with `POSTGRES_IMAGE`).

#### Measuring the end-to-end time (T150, SC-009, FR-028)

SC-009 allows 15 minutes from push to published image for one service. After a run of a service workflow on `main` (a push
publishes), read its wall-clock time and the time of each job:

```bash
# recent runs of one service workflow: id, event, conclusion, minutes from first job start to last update
gh run list --workflow cart.yml --branch main --limit 10 --json databaseId,event,conclusion,startedAt,updatedAt \
  --jq '.[] | [.databaseId, .event, .conclusion, (((.updatedAt|fromdate) - (.startedAt|fromdate)) / 60 * 10 | floor / 10)] | @tsv'

# one run: the end-to-end minutes, then the seconds of every job (gate, jar, image, publish, aggregate)
gh run view <run-id> --json startedAt,updatedAt,jobs --jq '
  "end-to-end: \(((.updatedAt|fromdate) - (.startedAt|fromdate)) / 60 * 10 | floor / 10) min",
  (.jobs[] | "\(.name)\t\((.completedAt|fromdate) - (.startedAt|fromdate)) s\t\(.conclusion)")'
```

With the jobs in parallel the end-to-end time is the longest path, `max(gate, jar + image) + publish`, not the sum. Record the
first accepted measurements here, per service, when real runs exist (none has run on a runner yet, so there is no number to
claim): date, service, run id, end-to-end minutes, slowest job.

| Date | Service | Run | End-to-end (min) | Slowest job |
| --- | --- | --- | --- | --- |
| (not measured yet) | | | | |

#### SBOM (T116, T167)

The software bill of materials of every service is the CycloneDX JSON that `anchore/syft` writes for the built image
(`service-ci.yml`, job `image`, artifact `sbom-<ctx>`, 30 days). It lists what is actually in the image (the layered jar
contents and the base image packages), which is what a consumer of the image needs, and it satisfies the SBOM requirement
(T116). A Gradle-side SBOM is **deferred**: if a bill of materials of the build (before packaging) is wanted, add the
CycloneDX Gradle plugin (`org.cyclonedx.bom`) in `build-logic` as a convention plugin applied to the deployable modules,
pin its version in `gradle/libs.versions.toml`, and publish `cyclonedxBom` output next to the syft artifact. Tracked in
`docs/build.md`, Follow-ups.

Choices: `osv-scanner` for dependencies (no API key, reads the resolved versions; OWASP dependency-check needs an NVD key
and a long database update); Trivy and syft run as containers pinned by tag and digest instead of third-party
actions (one action less to trust with the job token); the Pact CLI is the `pactfoundation/pact-cli` container. Pacticipant
names are the service names (`cart`, `catalog`, ...); a service with neither a consumer pact nor a published provider
verification is unknown to the broker, so set `PACT_CAN_I_DEPLOY=false` until it has one.

### Image naming and publication

`<REGISTRY_HOST>/<ctx>:<40-character commit SHA>` and `<REGISTRY_HOST>/<ctx>:<branch>` (`:main` after a merge), for example
`localhost:5443/cart:3f2a...` and `localhost:5443/cart:main`. The registry is the private `registry:3` of
`platform/ci-runner` (basic auth, TLS with a self-signed certificate); images are pullable only on that host or network
(`docker login <REGISTRY_HOST>`), local development keeps building from source with Compose. Without `REGISTRY_HOST` the
image is tagged `<ctx>:<sha>` locally and nothing is pushed.

Repository configuration used by the pipelines (Settings > Secrets and variables > Actions):

| Kind | Name | Purpose |
| --- | --- | --- |
| variable | `REGISTRY_HOST` | `host:port` of the private registry, e.g. `localhost:5443` |
| secret | `REGISTRY_USERNAME`, `REGISTRY_PASSWORD` | basic-auth credentials of the registry (only the push step sees them) |
| secret | `PACT_BROKER_URL`, `PACT_BROKER_USERNAME`, `PACT_BROKER_PASSWORD` | Pact Broker; without the URL every broker step is skipped |
| variable (optional) | `PACT_ENVIRONMENT`, `PACT_CAN_I_DEPLOY`, `PACT_CAN_I_DEPLOY_RETRIES`, `DEPENDENCY_SCAN_ENFORCE` | target environment of `can-i-deploy`; `false` switches the check off; how many 10-second polls `can-i-deploy` waits for unknown results (default 24); `false` makes the dependency scan advisory |

### Pact publish and `can-i-deploy` flow

1. `check` runs the consumer tests (`contractTest`, pacts to `build/pacts`) and the file-based provider verification
   (`<Ctx>ProviderVerificationTest`, `@PactFolder`).
2. With a broker configured, the service's own pacts (`<ctx>-*.json`, `platform-probe-<ctx>.json`) are published with the
   commit SHA as consumer version, the branch and the run URL. A pact whose content changed fires the broker webhook
   `contract_content_changed`, which dispatches the provider's workflow (`workflow_dispatch` inputs `reason`,
   `pact-url`, `consumer`; setup in [platform/ci-runner/README.md](../platform/ci-runner/README.md), "Pact Broker
   webhooks").
3. `contractVerify` runs again with the broker variables: `<Ctx>BrokerVerificationTest` (`@PactBroker`, guarded by
   `pactbroker.url`, same provider states as the folder verification through `<Ctx>ProviderStates`) verifies the
   consumer versions on `main`, deployed or released and on the same branch, or only the pact the webhook named, and
   publishes the results for `pact.provider.version` = commit SHA and `pact.provider.branch`.
4. `can-i-deploy` asks the broker whether this SHA is compatible with the versions it integrates with (the versions
   deployed to `PACT_ENVIRONMENT` when set), waiting while a dispatched provider run has not published its result yet.
   A failure names the pact, consumer and provider, which is the user-story-9 scenario "a change that breaks a contract
   fails naming the contract and the consumer".

### Required status checks and merge policy (T113, amended by T148, for the maintainer)

**Decision (2026-10-03, T148, amending T113 of 2026-10-02):** require **`pr-gate`** and **`services-aggregate`** on `main`.
The seven `service-ci / <ctx>` checks and `platform` stay informational on their own: they are path-filtered, GitHub
reports no check for a workflow its `paths:` filter skipped, and a required check that is never reported stays
"Expected - Waiting for status" and blocks the merge (a pull request that touches only `services/cart/**` would wait
forever for `service-ci / catalog` and the other five, a docs-only one for all of them). `services-aggregate` removes the
problem: it exists on every pull request and requires exactly the checks the change triggered.

**How `services-aggregate` works** (`.github/workflows/required-checks.yml`, `.github/scripts/services-aggregate.sh`, tests in
`.github/scripts/tests/test-services-aggregate.sh`):

1. It triggers on every pull request (`opened`, `synchronize`, `reopened`), with no `paths:` filter, and runs on a
   GitHub-hosted runner: it only reads the API, builds nothing, and it may wait up to 40 minutes, which on the single
   self-hosted runner would occupy the runner the service jobs wait for. Token: `contents`, `checks` and `pull-requests`
   read; no secrets.
2. It lists the files of the pull request (`gh api repos/<repo>/pulls/<n>/files`, renames count with both names) and feeds
   them to `.github/scripts/path-filter-check.sh`, the offline simulation of the `paths:` filters. A listed `<ctx>.yml`
   expects the check `service-ci / <ctx>`, `platform.yml` expects `platform`. A workflow that is not listed was not
   triggered, which counts as success.
3. It polls `gh api repos/<repo>/commits/<head sha>/check-runs?filter=latest` every 20 seconds until each expected check
   has been reported (10 minutes at most) and has completed (40 minutes at most). `success`, `neutral` and `skipped`
   pass; `failure`, `cancelled`, `timed_out`, `action_required`, `stale` and `startup_failure` fail at once, as does an
   expected check that never appears (re-run its workflow) or is still running at the deadline. A service or platform check
   that exists although its filter was not predicted is watched too. The step summary lists every watched check.
4. Pull requests from forks fail it (the service pipelines never run for them on the self-hosted runner), like `pr-gate`.
5. It always reports exactly one check, named `services-aggregate`.

Feature 003 configured branch protection through `scripts/bootstrap-repo.sh` (`required_status_checks`, `strict: true`).
Run once, as repository admin (nothing in this repository does it). The script form is convergent and replaces the list:

```bash
OWNER=<owner>; REPO=<repo>
scripts/bootstrap-repo.sh --owner "$OWNER" --name "$REPO" --yes \
  --require-check pr-gate --require-check services-aggregate
```

The same through the API (the PATCH form replaces the whole list; the POST form below only adds a context):

```bash
gh api -X PATCH "repos/$OWNER/$REPO/branches/main/protection/required_status_checks" -F strict=true \
  -f 'contexts[]=pr-gate' -f 'contexts[]=services-aggregate'

gh api -X POST "repos/$OWNER/$REPO/branches/main/protection/required_status_checks/contexts" \
  -f 'contexts[]=services-aggregate'
```

Do not require `service-ci / <ctx>` or `platform` directly: they are the very checks that go missing. `services-aggregate`
needs `pr-gate` to stay required as well, since it does not run the gate (`verify`, `mutation`, `hook-tests`).

Merge policy: a pull request merges when `pr-gate` and `services-aggregate` are green (the latter covers the service and
`platform` checks of the paths it touches); images are published only from `main`; fork pull requests never run on the
runner (a maintainer pushes the branch to this repository). `platform.yml` also runs after every merge to `main` and every
night, with the slow and chaos tags (see "Platform workflow").

### Platform workflow (`platform.yml`) and running it locally

Jobs: `platform` (45 minutes) validates the Compose files (every profile, also merged with `platform/perf/compose.perf.yml`),
shellchecks the scripts, lints the YAML, starts the `core` stack with `-f docker-compose.yml -f ../perf/compose.perf.yml`
(the override lifts the gateway's per-source-address rate limits: the suite signs in from one address, and with the default
tiers the run would measure the limiter), runs `smoke.sh --keep` and the **fast** acceptance scenarios
(`-Dcucumber.filter.tags="not @slow and not @chaos"`). `acceptance-slow` (75 minutes, after `platform`, never on pull
requests) rebuilds the stack the same way and runs `@slow and not @chaos`, then `@chaos` (which waits
`NOTIFICATION_FAILURE_TIMEOUT_MINUTES`, 15 by default, for a failing delivery to run out of retries). Both tear the stack
down always. `GATEWAY_PORT` and `GATEWAY_URL` are set once in the workflow `env:` (change both if 8080 is taken), and
`COMPOSE_FILE` carries the override into `smoke.sh` and the teardown. Triggers are in the workflow map above; the
non-pull-request runs share one concurrency queue because they publish the gateway on the same host port.

```bash
docker compose -f platform/compose/docker-compose.yml --env-file platform/compose/.env.example \
  --profile core --profile observability --profile ci config -q
shellcheck -x -P SCRIPTDIR platform/compose/scripts/*.sh platform/ci-runner/scripts/*.sh platform/perf/*.sh
pip install yamllint==1.38.0 && yamllint -d relaxed --no-warnings platform/observability \
  platform/compose/docker-compose.yml platform/perf/compose.perf.yml platform/ci-runner/docker-compose.yml

cp platform/compose/.env.example platform/compose/.env           # replace INTERNAL_API_TOKEN for anything shared
export COMPOSE_FILE=docker-compose.yml:../perf/compose.perf.yml GATEWAY_PORT=8080
(cd platform/compose && BUILDAH_FORMAT=docker docker compose --profile core up -d --build)
platform/compose/scripts/smoke.sh --no-build --keep               # health, gateway 200, ports not published
export GATEWAY_URL=http://localhost:$GATEWAY_PORT
./gradlew -q :acceptance:test -Dcucumber.filter.tags="not @slow and not @chaos"       # fast suite
./gradlew -q :acceptance:test -Dcucumber.filter.tags="@slow and not @chaos"           # slow suite
./gradlew -q :acceptance:test -Dcucumber.filter.tags="@chaos"                         # chaos (Mailpit chaos API)
(cd platform/compose && docker compose --profile core --profile observability --profile ci down -v)
```

One service pipeline can be rehearsed the same way: the Gradle `check` of its modules, then
`docker build -f platform/docker/Dockerfile --build-arg SERVICE_MODULE=:services:cart:infrastructure -t cart:local .`,
`docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.75.0 image --severity CRITICAL --ignore-unfixed cart:local`
and `.github/scripts/gradle-deps-to-lockfile.sh` on the output of `./gradlew -q :services:cart:infrastructure:dependencies --configuration runtimeClasspath`.

### Updating pinned tools

Actions are pinned as above. Tool images are pinned by tag and digest in the `env:` block of `service-ci.yml`
(`PACT_CLI_IMAGE`, `OSV_SCANNER_IMAGE`, `TRIVY_IMAGE`, `SYFT_IMAGE`) and `platform.yml` (`SHELLCHECK_IMAGE`,
`YAMLLINT_PYTHON_IMAGE`, `YAMLLINT_VERSION`), and in `platform/ci-runner/docker-compose.yml` (runner, registry, Pact
Broker, PostgreSQL). To refresh one, look up the tag and its digest (`docker buildx imagetools inspect <image>:<tag>`
or the registry's tags page) and change both together. The tags in use when this was written: `pact-cli` 1.5.0.5,
`osv-scanner` v2.6.0, `trivy` 0.75.0, `syft` v1.54.0, `shellcheck` v0.11.0, `registry` 3.1.2,
`github-runner` 2.337.0-ubuntu-noble, `pact-broker` 3.0.0-pactbroker2.121.2.
