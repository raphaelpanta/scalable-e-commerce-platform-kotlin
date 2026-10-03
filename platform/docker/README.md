# Shared service image recipe

`Dockerfile` is the single multi-stage recipe for every deployable of the platform (the gateway and the
`infrastructure` module of each service). It is used by:

- the Gradle `dockerImage` task of the `docker-image` convention (`./gradlew :services:<name>:infrastructure:dockerImage`),
- Docker Compose (`platform/compose/docker-compose.yml`, `build:` blocks of `gateway` and the six services).

Per-service Dockerfiles are therefore not needed: the service is chosen with a build argument.

## Build argument

| Argument | Meaning | Examples |
|---|---|---|
| `SERVICE_MODULE` | Gradle path of the module that produces the Spring Boot jar (`bootJar`); required, the build fails when empty | `:services:gateway`, `:services:catalog:infrastructure` |

Always build from the **repository root** (the build context is the whole monorepo, `.dockerignore` trims it):

```bash
docker build -f platform/docker/Dockerfile --build-arg SERVICE_MODULE=:services:catalog:infrastructure -t catalog:local .
```

## Stages

1. `build` (`eclipse-temurin:25-jdk`): copies the repository and runs `./gradlew :<module>:bootJar` with the Gradle user home
   mounted as a BuildKit cache (`--mount=type=cache,target=/root/.gradle`), so Gradle distributions and dependencies are
   downloaded once and shared by the seven image builds. The cache is local to the engine and never ends up in an image layer.
2. `layers`: extracts the Spring Boot layered jar (`-Djarmode=tools extract --layers --launcher`), so that dependencies
   change rarely and rebuilds only replace the small `application` layer.
3. runtime (`eclipse-temurin:25-jre`): non-root user `app` (uid/gid 10001), the four extracted layers, `EXPOSE 8080 8081`
   (API and management), `ENTRYPOINT java ... JarLauncher`.

Base images are pinned by digest; refresh them deliberately.

## Runtime settings

- `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -Dnetworkaddress.cache.ttl=5`: heap sized from the container memory limit and a
  5 s JVM DNS cache so that DNS-based discovery notices added and removed replicas quickly.
- `HEALTHCHECK` probes `GET /actuator/health/readiness` on port 8081 (bash `/dev/tcp`, the JRE image has no curl) and
  expects `"status":"UP"`. Compose declares the same check (`x-app` in `platform/compose/docker-compose.yml`) and uses it for
  `depends_on: condition: service_healthy` and `docker compose ps`; the CI start-and-health step
  (`.github/scripts/image-health.sh`) polls the same path. Every service and the gateway enable the probe groups
  (`management.endpoint.health.probes.enabled=true`, readiness = `readinessState`, plus the database contributor `db`
  in catalog; docs/service-conventions.md section 2).

## How Compose uses it

```yaml
catalog:
  build:
    context: ../..                          # repository root
    dockerfile: platform/docker/Dockerfile
    args:
      SERVICE_MODULE: :services:catalog:infrastructure
```

`docker compose build catalog` (or `up -d --build catalog`) rebuilds a single service; see `platform/compose/README.md`.

## Podman note

`HEALTHCHECK` is part of the Docker image format only. When building with Podman directly, pass `--format docker`
(or export `BUILDAH_FORMAT=docker`), otherwise the health check is dropped from the image and Compose never sees the
service as healthy.
