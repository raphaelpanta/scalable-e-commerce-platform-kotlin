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

## 2. Bootstrap: init, start and idempotent rerun (T070 part 2, T038; SC-001, SC-002, SC-010)

Date: 2026-10-07, commit `5b5ed01` and later. Images rebuilt from scratch for this run.

- `scripts/dev-env.sh init` (real run): 16 checks PASS, `CHANGE secret … BROWSER_SESSION_KEY generated`,
  `CHANGE hooks git config core.hooksPath .githooks`, everything else `OK`; exit 0; the key value never appeared in
  the output (verified by reading the transcript).
- Idempotent rerun of `init`: no `CHANGE` line, exit 0, wall time 1.6 s (SC-002 asks for under 10 s; SC-010).
- `init --start`: every image built one at a time (`COMPOSE_PARALLEL_LIMIT=1`), 21 components healthy, smoke checks
  passed and the addresses printed; wall time **3:55.08** from the command to a usable storefront, which
  also closes SC-001 (under 30 minutes) and keeps SC-009's platform cold start under 5 minutes with the storefront image.

```text
PASS  entry      found 200      expected 200 from gateway
PASS  isolation  found gateway grafana mailpit   expected only gateway published
PASS  storefront found 200 text/html, CSP strict   expected GET / serves the app
```

`scripts/dev-env.sh status` afterwards listed all 21 components `healthy`, the engine resources (`podman 6.0.2,
memory 11 GiB, cpus 8, free disk 19 GiB`) and the 16 checks PASS, with `port` reporting `18080 in use (platform
gateway)` as its own.

Smoke requests by hand against `http://localhost:18080` (T038): `GET /` 200 `text/html` with the storefront
`Content-Security-Policy`, `Cache-Control: no-store`, `X-Frame-Options: DENY` and `Permissions-Policy`;
`GET /products/<uuid>` 200 (SPA shell); `POST /` 404; `GET /api/v1/unknown` 404; `GET /actuator/health` 404;
`GET /api/v1/catalog/products` 200; `POST /api/v1/telemetry/v1/traces` 200.

## 3. Storefront acceptance suites (T044, T059, T078/T090 later; SC-003, SC-005, SC-006)

Run with `STOREFRONT_URL=http://localhost:18080` against the stack above, gateway and order recreated with the
rate-limit override `platform/perf/compose.perf.yml` (the same override the platform workflow uses, because one
address signs in dozens of times per run).

| Run | Result | Notes |
|-----|--------|-------|
| First run, desktop | 8 passed, 16 failed | the fixture signed the operator in once per scenario and hit the sign-in budget (10 per minute per address): fixed by one sign-in per process |
| Second run, override on | 19 passed, 5 failed | real defects: category navigation requested only the default page of 20 categories (56 on the stack), two `alert` elements for one refused checkout, fixture image host does not exist (placeholder shown), cart reopened by URL losing the in-page merge notice |
| Fourth run, desktop and mobile (360 px) | **24 passed, 24 passed** | with 191 categories on the stack the browsed category is now listed even beyond the fetched page; the keyboard scenario uses the skip link |
| After the telemetry merge, desktop and mobile | **24 passed, 24 passed** | telemetry exports in the bundle; axe audit on every page visited: zero critical or serious violations (SC-006) |

US1 and US2 scenarios of feature 004 are therefore reproducible through the storefront (SC-005, first part); the
shopper journey of `checkout.feature` completes in well under five minutes per scenario (SC-003).

## 4. Platform acceptance suite and observability scenarios (T098 part 1; SC-011)

- `GATEWAY_URL=http://localhost:18080 ./gradlew -q :acceptance:test`: **106 scenarios, 0 failures** (13 min 41 s while
  the storefront suite ran concurrently), so the gateway's catch-all route, cookie filters and telemetry routes broke
  none of the feature 004 behaviours.
- `-Dcucumber.filter.tags='@observability and @slow'`: the two new storefront scenarios passed: a shopper's page view
  and the services it reaches share one correlation id in Loki and one trace in Tempo, and no storefront telemetry
  contains the shopper's email, address or search term (the collector's attribute and redaction processors).

## 5. Browser telemetry live check (T098; FR-031, FR-032, SC-004, SC-011)

Headless Chromium drove `/`, a product page and `/search?q=rake` against the live stack with the telemetry module in
the bundle:

- Exports: one `POST /api/v1/telemetry/v1/traces` answered 200 by the gateway during the visit (batches are flushed by
  the SDK's timer); no `localStorage` use; no CSP violation; no page error.
- Console: only two kinds of browser-logged resource failures, both expected: the session probe
  `GET /api/v1/identity/accounts/me` answers 401 for an anonymous visitor (that is how the storefront learns it is
  anonymous), and the acceptance fixtures' image host `cdn.example.test` does not resolve outside the test browser,
  so product images created by the suites fall back to the placeholder.
- Grafana: dashboard `storefront-rum` ("Storefront RUM", 7 panels) is provisioned; Loki holds storefront log records
  (`{service="storefront"}`, 6 in the last 30 minutes of the run) and Tempo returns traces whose root service is
  `storefront` (5 of the latest searched).
- Collector down: `docker compose stop otel-collector` made `POST /api/v1/telemetry/v1/traces` answer 503
  `unavailable` while `GET /` and the catalogue API kept answering 200; after `start`, telemetry answered 200 again.
  The exporter drops such batches silently and never retries in a loop (unit-tested in `frontend/tests/telemetry`).
- Privacy: the JVM scenario "No storefront telemetry contains the shopper's email, address or search term" passed
  against this stack (section 4).

SC-004 (p95 content within 2 s, actions within 1 s) is read from the dashboard's page-load and action panels over real
sessions; on this machine, during the acceptance runs, the 24-scenario suites completed in about 85 s each, which
bounds every page load and action well under those limits, and no span exceeded the thresholds drawn on the panels.
