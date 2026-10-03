package com.ecommerce.gateway.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component

/**
 * Connects the `OTEL` appender of the gateway's `logback-spring.xml` to the application's [OpenTelemetry] once all
 * singletons exist, so every log line (the access line with `correlationId` included) is also exported as an OTLP
 * log record (`management.opentelemetry.logging.export.otlp.endpoint`) and reaches Loki through the collector. The
 * gateway does not depend on platform-core, which wires the services the same way.
 */
@Component
class OpenTelemetryAppenderInstaller(
    private val openTelemetry: ObjectProvider<OpenTelemetry>,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        openTelemetry.ifAvailable { OpenTelemetryAppender.install(it) }
    }
}
