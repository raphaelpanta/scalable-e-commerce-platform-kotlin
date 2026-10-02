package com.ecommerce.platform.observability

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Adds the platform's lowest-precedence defaults, so every service behaves the same without repeating them in its
 * `application.yml` (which still wins): management on port 8081 exposing only `health` and `prometheus` with the
 * readiness/liveness probes; ECS structured console logging naming the service; percentile histograms for HTTP
 * server requests; full trace sampling and OTLP/HTTP export of metrics, traces and logs to
 * `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4318`) with short timeouts, so that a missing collector
 * costs at most one throttled warning per exporter instead of slowing requests or failing the start; Reactor
 * context propagation (correlation id in the MDC); and the security settings mapped from the environment
 * (`JWKS_URI`, `JWT_ISSUER`, `JWT_AUDIENCE`, `INTERNAL_API_TOKEN`; the token has no default).
 */
class PlatformDefaultsEnvironmentPostProcessor :
    EnvironmentPostProcessor,
    Ordered {
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        if (!environment.propertySources.contains(PROPERTY_SOURCE)) {
            environment.propertySources.addLast(MapPropertySource(PROPERTY_SOURCE, DEFAULTS))
        }
    }

    companion object {
        /** Name of the property source holding the defaults. */
        const val PROPERTY_SOURCE: String = "platformDefaults"

        private const val OTLP = "\${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}"
        private const val CONNECT_TIMEOUT = "1s"
        private const val EXPORT_TIMEOUT = "5s"

        /** The defaults, by property name. */
        val DEFAULTS: Map<String, Any> =
            mapOf(
                "management.server.port" to "8081",
                "management.endpoints.web.exposure.include" to "health,prometheus",
                "management.endpoint.health.probes.enabled" to "true",
                "management.endpoint.health.show-details" to "never",
                "management.metrics.distribution.percentiles-histogram.http.server.requests" to "true",
                "management.tracing.sampling.probability" to "1.0",
                "management.opentelemetry.tracing.export.otlp.endpoint" to "$OTLP/v1/traces",
                "management.opentelemetry.tracing.export.otlp.connect-timeout" to CONNECT_TIMEOUT,
                "management.opentelemetry.tracing.export.otlp.timeout" to EXPORT_TIMEOUT,
                "management.opentelemetry.logging.export.otlp.endpoint" to "$OTLP/v1/logs",
                "management.opentelemetry.logging.export.otlp.connect-timeout" to CONNECT_TIMEOUT,
                "management.opentelemetry.logging.export.otlp.timeout" to EXPORT_TIMEOUT,
                "management.otlp.metrics.export.url" to "$OTLP/v1/metrics",
                "management.otlp.metrics.export.connect-timeout" to CONNECT_TIMEOUT,
                "management.otlp.metrics.export.read-timeout" to EXPORT_TIMEOUT,
                "logging.structured.format.console" to "ecs",
                "logging.structured.ecs.service.name" to "\${spring.application.name:application}",
                "spring.reactor.context-propagation" to "auto",
                "platform.security.jwks-uri" to "\${JWKS_URI:http://localhost:8080/.well-known/jwks.json}",
                "platform.security.issuer" to "\${JWT_ISSUER:https://identity.ecommerce.local}",
                "platform.security.audience" to "\${JWT_AUDIENCE:ecommerce-api}",
                "platform.security.internal-token" to "\${INTERNAL_API_TOKEN:}",
            )
    }
}
