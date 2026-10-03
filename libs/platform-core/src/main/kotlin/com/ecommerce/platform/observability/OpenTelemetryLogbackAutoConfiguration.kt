package com.ecommerce.platform.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Connects Logback to OpenTelemetry: the `OTEL` appender declared in platform-core's `logback-spring.xml` turns
 * every log event into an OpenTelemetry log record, which Boot's OTLP log exporter
 * (`management.opentelemetry.logging.export.otlp.*`, set by [PlatformDefaultsEnvironmentPostProcessor]) sends to the
 * collector and on to Loki, with the resource attribute `service.name` and the request's trace context.
 */
@AutoConfiguration(
    afterName = ["org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration"],
)
@ConditionalOnClass(OpenTelemetry::class, OpenTelemetryAppender::class)
class OpenTelemetryLogbackAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun openTelemetryAppenderInstaller(openTelemetry: ObjectProvider<OpenTelemetry>): OpenTelemetryAppenderInstaller =
        OpenTelemetryAppenderInstaller(openTelemetry)
}

/**
 * Hands the application's [OpenTelemetry] to every [OpenTelemetryAppender] of the Logback configuration once all
 * singletons exist (the SDK, its logger provider and exporters included). Without an [OpenTelemetry] bean nothing is
 * installed and the appender stays inert.
 */
class OpenTelemetryAppenderInstaller(
    private val openTelemetry: ObjectProvider<OpenTelemetry>,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        openTelemetry.ifAvailable { OpenTelemetryAppender.install(it) }
    }
}
