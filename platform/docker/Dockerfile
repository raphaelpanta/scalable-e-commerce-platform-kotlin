# syntax=docker/dockerfile:1
# Shared multi-stage image recipe for every service; built by `./gradlew :services:<name>:infrastructure:dockerImage`
# from the repository root with --build-arg SERVICE_MODULE=<gradle path of the infrastructure module>.
# Base images are pinned by digest (resolved 2026-10-02); refresh them deliberately (docs/build.md, Follow-ups).

ARG SERVICE_MODULE

FROM eclipse-temurin:25-jdk@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc AS build
ARG SERVICE_MODULE
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    test -n "${SERVICE_MODULE}" \
    && ./gradlew -q --no-daemon "${SERVICE_MODULE}:bootJar" \
    && module_dir="$(echo "${SERVICE_MODULE}" | sed 's#^:##; s#:#/#g')" \
    && find "${module_dir}/build/libs" -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/app.jar \;

FROM eclipse-temurin:25-jdk@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc AS layers
WORKDIR /layers
COPY --from=build /workspace/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

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
# The JRE image ships neither curl nor wget; bash's /dev/tcp is enough for the actuator probe. Every service enables
# the readiness health group on the management port 8081 (docs/service-conventions.md section 2).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]
USER app
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
