# syntax=docker/dockerfile:1
# Shared multi-stage image recipe for every service; built by Docker Compose (platform/compose/docker-compose.yml) and
# by `./gradlew :services:<name>:infrastructure:dockerImage` from the repository root with
# --build-arg SERVICE_MODULE=<gradle path of the deployable module> (and, optionally,
# --build-arg APP_JAR=<pre-built boot jar inside the context>).
# Base images are pinned by digest (resolved 2026-10-02); refresh them deliberately (docs/build.md, Follow-ups).
#
# One shared build stage (T132, SC-006): `build` compiles the boot jar of EVERY deployable (the gateway and each
# services/*/infrastructure module) in a single Gradle invocation and does not depend on SERVICE_MODULE, so the seven
# image builds of `docker compose up --build` send the same context through the same instructions and the engine
# reuses the stage from its layer cache after the first image (BuildKit and buildah/Podman alike). Only `layers` and
# the runtime stage differ per SERVICE_MODULE. Never reference SERVICE_MODULE in `build`: that would split the cache
# per service again (platform/docker/README.md).

FROM eclipse-temurin:25-jdk@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc AS build
# Optional: path, relative to the build context, of an already built boot jar (CI: the artifact of the `jar` job of
# .github/workflows/service-ci.yml). When set, the stage copies it to /jars/prebuilt.jar and does not compile; empty
# (the default, as Compose and `dockerImage` use it) builds every deployable's jar here with Gradle.
ARG APP_JAR=""
WORKDIR /workspace
COPY . .
# Deployables: services/gateway and every services/<name>/infrastructure directory (a service added with
# `./gradlew newService` is picked up without editing this file). Jar names follow the module path:
# :services:catalog:infrastructure -> /jars/services-catalog-infrastructure.jar.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    set -e; \
    mkdir -p /jars; \
    if [ -n "${APP_JAR}" ]; then \
      test -f "${APP_JAR}"; \
      cp "${APP_JAR}" /jars/prebuilt.jar; \
    else \
      modules="services/gateway $(ls -d services/*/infrastructure)"; \
      tasks=""; \
      for dir in ${modules}; do tasks="${tasks} :$(echo "${dir}" | tr / :):bootJar"; done; \
      ./gradlew -q --no-daemon ${tasks}; \
      for dir in ${modules}; do \
        jar="$(find "${dir}/build/libs" -name '*.jar' ! -name '*-plain.jar')"; \
        test "$(echo "${jar}" | wc -l)" -eq 1 && test -f "${jar}"; \
        cp "${jar}" "/jars/$(echo "${dir}" | tr / -).jar"; \
      done; \
    fi

# Per service from here on: picks the jar of SERVICE_MODULE (or the pre-built one) out of the shared stage, whose file
# system it inherits (no copy of the seven jars), and extracts the Spring Boot layers.
FROM build AS layers
ARG SERVICE_MODULE
WORKDIR /layers
RUN set -e; \
    if [ -f /jars/prebuilt.jar ]; then \
      jar=/jars/prebuilt.jar; \
    else \
      test -n "${SERVICE_MODULE}" || { echo "build-arg SERVICE_MODULE is required" >&2; exit 1; }; \
      jar="/jars/$(echo "${SERVICE_MODULE}" | sed 's#^:##; s#:#-#g').jar"; \
      test -f "${jar}" || { echo "no boot jar for ${SERVICE_MODULE} (${jar})" >&2; exit 1; }; \
    fi; \
    java -Djarmode=tools -jar "${jar}" extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
# MaxRAMPercentage sizes the heap from the container limit; networkaddress.cache.ttl=5 keeps JVM DNS lookups short-lived
# so that platform DNS discovery (Compose service names, later Kubernetes Services) drops removed replicas within
# 5 seconds (research.md section 2, FR-024, SC-008).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dnetworkaddress.cache.ttl=5"
EXPOSE 8080 8081
# The JRE image ships neither curl nor wget; bash's /dev/tcp is enough for the actuator probe. Every service and the
# gateway expose the readiness health group on the management port 8081 (docs/service-conventions.md section 2): the
# container is healthy once the application accepts traffic (and, where the group includes it, its database answers).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]
USER app
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
