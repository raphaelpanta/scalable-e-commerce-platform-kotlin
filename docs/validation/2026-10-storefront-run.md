# Feature 005 validation record: web storefront and local development bootstrap

Live runs of the stack-dependent tasks of [tasks.md](../../specs/005-storefront-dev-bootstrap/tasks.md) on the
development machine (macOS, Podman 6.0.2 behind the Docker API, machine 8 CPUs / 11.6 GiB, Node 24.18.1, default
`java` 27 with the JDK 25 toolchain provisioned by Gradle). Each section names the task it closes and maps the
observations to the success criteria of [spec.md](../../specs/005-storefront-dev-bootstrap/spec.md).

## 1. Bootstrap checks and dry run (T070, part 1; SC-002)

Date: 2026-10-06, commit `00cf517` and later.

The first `scripts/dev-env.sh check` on this machine reported 12 PASS, 3 FAIL, 1 SKIP in 2.5 s. The three failures
were defects of the checks, not of the machine, and were fixed before anything else:

| Check | First result | Cause | Fix (commit `00cf517`) |
|-------|--------------|-------|------------------------|
| `jdk` | `FAIL found 27 expected 25` | only the default `java` was consulted, while Gradle runs on the JDK 25 toolchain it provisioned under `~/.gradle/jdks` | a JDK of the pinned major found under `~/.gradle/jdks` (any of Gradle's unpack layouts), in SDKMAN or through macOS `java_home` passes: `found 27 (default), 25.0.4.1 via Gradle toolchain` |
| `memory`, `cpus` | `FAIL found none` | the `docker` command is Podman's own CLI; its `info --format json` reports `host.memTotal` and `host.cpus`, not `MemTotal` and `NCPU` | resources are read from the JSON form of `info` for both engines, falling back to the Go-template form |
| `engine` | `PASS found docker 6.0.2` (misdetected) | Podman's version JSON carries no "Podman" marker; its plain `docker version` output does | detection reads the plain output: `found podman 6.0.2 (docker socket)`, which also activates the `podman` configuration check |

After the fix:

```text
$ scripts/dev-env.sh check
PASS  jdk        found 27 (default), 25.0.4.1 via Gradle toolchain   expected 25
PASS  engine     found podman 6.0.2 (docker socket)   expected docker or podman reachable
PASS  compose    found v5.6.0 (docker compose)   expected v2
PASS  memory     found 11 GiB   expected >= 10 GiB
PASS  cpus       found 8        expected >= 4
PASS  disk       found 25 GiB free (repository)   expected >= 15 GiB free
PASS  podman     found BUILDAH_FORMAT=docker, ryuk disabled   expected BUILDAH_FORMAT=docker, ryuk disabled
PASS  node       found 24.18.1   expected 24
PASS  npm        found 11.16.0   expected >= 11
PASS  gitleaks   found 8.30.1   expected installed
PASS  curl       found 8.7.1    expected installed
PASS  jq         found 1.8.2    expected installed
PASS  openssl    found 3.6.5    expected ed25519 capable
PASS  git        found 2.56.0   expected >= 2.9
PASS  port       found 18080 free   expected 18080 free
PASS  network    found HTTP 401   expected registry reachable
checks: 16 total, 16 PASS, 0 FAIL, 0 SKIP
```

Wall time 2.5 s (SC-002 asks for under 30 s). `GATEWAY_PORT=18080` comes from the existing `platform/compose/.env` of
this machine (8080 is taken by another local service).

`scripts/dev-env.sh init --dry-run` on the same clone (which already had `.env`, the signing key, `BUILDAH_FORMAT` and
the Testcontainers property from feature 004) printed only the two changes still missing, without values:

```text
OK      env        platform/compose/.env exists
DRY-RUN secret     IDENTITY_SIGNING_KEY already set, BROWSER_SESSION_KEY would be generated
DRY-RUN: generate BROWSER_SESSION_KEY in platform/compose/.env
OK      port       GATEWAY_PORT 18080 free
DRY-RUN hooks      git config core.hooksPath .githooks
DRY-RUN: git config core.hooksPath .githooks
OK      engine     BUILDAH_FORMAT=docker already set
OK      ryuk       ryuk.disabled=true in ~/.testcontainers.properties
```

Exit 0, nothing written (`.env` unchanged, `git config core.hooksPath` still unset afterwards). The real `init`,
`init --start` timing (SC-001) and the idempotent rerun (SC-010) follow in section 2 once the storefront image and the
gateway routes are on `main`.
