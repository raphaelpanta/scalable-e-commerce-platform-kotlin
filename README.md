# Scalable E-Commerce Platform (Kotlin)

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

A scalable e-commerce platform built as a Kotlin monorepo: independently deployable microservices on
Spring Boot (WebFlux and coroutines) that communicate over Kafka, each designed with hexagonal
architecture and domain-driven design, plus a TypeScript and React storefront.

The project is developed spec-first with [Spec Kit](https://github.com/github/spec-kit). Its rules live
in the [constitution](.specify/memory/constitution.md) and every feature has a specification, plan and
task list under [`specs/`](specs/).

## Architecture summary

- **Monorepo**: one Gradle multimodule build written in the Kotlin DSL, with a version catalog
  (`gradle/libs.versions.toml`) and convention plugins in `build-logic/`.
- **Services**: one bounded context per microservice; no shared databases and no shared domain code.
  Services integrate through versioned HTTP and event contracts, verified with consumer-driven tests.
- **Layering**: `domain` (pure Kotlin, no framework types), `application` (use cases, authorization),
  `adapters` (HTTP, persistence, messaging). Dependencies point inward only.
- **Runtime**: Spring Boot 3 with WebFlux and coroutines, R2DBC persistence, reactive Redis and Kafka.
- **Quality**: layered tests (unit, property, integration with Testcontainers, contract, behaviour),
  mutation testing, ktlint and detekt, dependency vulnerability scanning.
- **Security by design**: threat model per feature, deny-by-default authorization, no secrets in
  version control. See Principle III of the [constitution](.specify/memory/constitution.md).

Layout and decisions are documented feature by feature in [`specs/`](specs/); the platform scope is in
`specs/004-ecommerce-platform-mvp/`.

## Build and verify

Full build documentation: [docs/build.md](docs/build.md) (layout, test layers, adding a module or dependency, quality gates) and CI in [docs/ci-cd.md](docs/ci-cd.md), system overview in [docs/architecture.md](docs/architecture.md).

The Gradle build is introduced by `specs/002-gradle-monorepo-bootstrap/`. Once it is present, run the
full quality gate from the repository root:

```bash
./gradlew check          # compile, lint, tests (quiet, failures only)
```

## Quick start

```bash
scripts/dev-env.sh init --start   # check the machine, configure the clone, start the platform and the storefront
scripts/dev-env.sh status         # components, addresses, engine resources, checks
scripts/dev-env.sh down           # stop everything, keep the data
```

The storefront and API answer on http://localhost:8080 (Grafana on :3000, Mailpit on :8025). The command, its
flags and the manual steps it replaces: [docs/dev-environment.md](docs/dev-environment.md) and
[docs/running-locally.md](docs/running-locally.md).

Repository tooling (shell scripts, no Gradle needed):

```bash
scripts/tests/run-all.sh          # offline tests of the repository scripts, prints PASS: N tests
scripts/lint.sh                   # shellcheck over the repository scripts
scripts/verify-repo.sh            # verify the published repository (visibility, protection, scanning)
```

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) first. In short: run `scripts/dev-env.sh init` (it enables the
secret-scanning hook, which needs [gitleaks](https://github.com/gitleaks/gitleaks)), work on a
branch, open a pull request and complete the checklist in the template. `main` is protected and pull
requests are squash-merged. Please follow the [code of conduct](CODE_OF_CONDUCT.md) and report
vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## How this repository was published

The repository was created and hardened with an idempotent script, so the same setup can be repeated for
future repositories of the platform:

```bash
scripts/bootstrap-repo.sh --dry-run        # read-only rehearsal: prints every step, changes nothing
scripts/bootstrap-repo.sh                  # publish (asks you to type the repository name to confirm)
scripts/verify-repo.sh                     # check the result from the outside
```

It requires `git`, an authenticated [GitHub CLI](https://cli.github.com/) (`gh auth login`) and
`gitleaks` (installed with Homebrew when missing). Flags: `--owner`, `--name`, `--require-check <context>`,
`--admin-bypass`, `--yes`, `--verbose`. The procedure and its safeguards are specified in
`specs/003-github-public-repo/`.

## License

Released under the [MIT License](LICENSE).
