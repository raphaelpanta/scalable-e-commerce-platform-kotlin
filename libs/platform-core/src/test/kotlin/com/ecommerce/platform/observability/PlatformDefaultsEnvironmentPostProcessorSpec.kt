package com.ecommerce.platform.observability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class PlatformDefaultsEnvironmentPostProcessorSpec :
    FunSpec({
        fun environment(vararg properties: Pair<String, Any>): StandardEnvironment =
            StandardEnvironment().apply {
                propertySources.addFirst(MapPropertySource("test", mapOf(*properties)))
                PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(this, SpringApplication())
            }

        test("defaults apply when nothing else is configured") {
            val env = environment("spring.application.name" to "catalog")
            env.getProperty("management.server.port") shouldBe "8081"
            env.getProperty("management.endpoints.web.exposure.include") shouldBe "health,prometheus"
            env.getProperty("management.endpoint.health.probes.enabled") shouldBe "true"
            env.getProperty("logging.structured.format.console") shouldBe "ecs"
            env.getProperty("logging.structured.ecs.service.name") shouldBe "catalog"
            env.getProperty("management.tracing.sampling.probability") shouldBe "1.0"
            env.getProperty("management.metrics.distribution.percentiles-histogram.http.server.requests") shouldBe
                "true"
            env.getProperty("spring.reactor.context-propagation") shouldBe "auto"
            env.getProperty("platform.security.issuer") shouldBe "https://identity.ecommerce.local"
            env.getProperty("platform.security.audience") shouldBe "ecommerce-api"
            env.getProperty("platform.security.internal-token") shouldBe ""
        }

        test("OTLP endpoints derive from OTEL_EXPORTER_OTLP_ENDPOINT") {
            environment().getProperty("management.opentelemetry.tracing.export.otlp.endpoint") shouldBe
                "http://localhost:4318/v1/traces"
            val env = environment("OTEL_EXPORTER_OTLP_ENDPOINT" to "http://otel-collector:4318")
            env.getProperty("management.opentelemetry.tracing.export.otlp.endpoint") shouldBe
                "http://otel-collector:4318/v1/traces"
            env.getProperty("management.opentelemetry.logging.export.otlp.endpoint") shouldBe
                "http://otel-collector:4318/v1/logs"
            env.getProperty("management.otlp.metrics.export.url") shouldBe "http://otel-collector:4318/v1/metrics"
        }

        test("the environment variables of the deployment are mapped") {
            val env =
                environment(
                    "JWKS_URI" to "http://identity:8080/.well-known/jwks.json",
                    "JWT_ISSUER" to "https://issuer",
                    "INTERNAL_API_TOKEN" to "s3cret",
                )
            env.getProperty("platform.security.jwks-uri") shouldBe "http://identity:8080/.well-known/jwks.json"
            env.getProperty("platform.security.issuer") shouldBe "https://issuer"
            env.getProperty("platform.security.internal-token") shouldBe "s3cret"
        }

        test("anything the service configures wins and the source is added once") {
            val env = environment("management.server.port" to "9001")
            env.getProperty("management.server.port") shouldBe "9001"
            PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(env, SpringApplication())
            env.propertySources.count { it.name == PlatformDefaultsEnvironmentPostProcessor.PROPERTY_SOURCE } shouldBe 1
            env.propertySources.last().name shouldBe PlatformDefaultsEnvironmentPostProcessor.PROPERTY_SOURCE
        }
    })
