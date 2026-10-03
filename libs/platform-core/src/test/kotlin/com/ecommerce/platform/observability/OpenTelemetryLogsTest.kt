package com.ecommerce.platform.observability

import com.ecommerce.platform.correlation.CorrelationIdWebFilter
import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.testapp.PlatformWebTest
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Logs reach OpenTelemetry (and so Loki through the collector): platform-core's `logback-spring.xml` attaches the
 * `OTEL` appender to the root logger next to the ECS console, and [OpenTelemetryAppenderInstaller] connects it to the
 * application's SDK. An in-memory exporter stands in for the OTLP one (disabled in tests).
 */
@Import(OpenTelemetryLogsTest.InMemoryLogs::class)
class OpenTelemetryLogsTest(
    @LocalServerPort port: Int,
) : PlatformWebTest(port) {
    @BeforeEach
    fun clear() = exporter.reset()

    @Test
    fun `the OTEL appender sits on the root logger next to the console`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as LogbackLogger
        val appenders = root.iteratorForAppenders().asSequence().toList()
        appenders.map { it.name } shouldBe listOf("CONSOLE", "OTEL")
        appenders.filterIsInstance<OpenTelemetryAppender>().single().isStarted shouldBe true
    }

    @Test
    fun `the access line of a request becomes a log record carrying the correlation id and the service`() {
        get("3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13")
        val record = accessRecords().single()
        record.bodyValue?.asString() shouldBe "GET /api/v1/public/correlation 200"
        record.severity shouldBe Severity.INFO
        record.attributes.get(CORRELATION_ID) shouldBe "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
        record.attributes.get(ORIGINAL_CORRELATION_ID) shouldBe null
        record.resource.getAttribute(SERVICE_NAME) shouldBe "platform-test"
    }

    @Test
    fun `a refused inbound id is kept as originalCorrelationId next to the generated one`() {
        get("not valid; drop table")
        val record = accessRecords().single()
        record.attributes.get(CORRELATION_ID).orEmpty() shouldMatch "[0-9a-f-]{36}"
        record.attributes.get(ORIGINAL_CORRELATION_ID) shouldBe "not valid; drop table"
    }

    @Test
    fun `any line logged with the correlation id in the MDC carries it, and no code location`() {
        MDC.putCloseable(CorrelationIds.MDC_KEY, "abc-123").use { log.warn("stock below threshold") }
        val records = exporter.finishedLogRecordItems.filter { it.instrumentationScopeInfo.name == log.name }
        records shouldHaveSize 1
        records.single().let { record ->
            record.bodyValue?.asString() shouldBe "stock below threshold"
            record.severity shouldBe Severity.WARN
            record.attributes.get(CORRELATION_ID) shouldBe "abc-123"
            record.attributes.get(AttributeKey.stringKey("code.function.name")) shouldBe null
        }
    }

    private fun get(correlationId: String) {
        client
            .get()
            .uri("/api/v1/public/correlation")
            .header(CorrelationIds.HEADER, correlationId)
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun accessRecords(): List<LogRecordData> =
        exporter.finishedLogRecordItems.filter {
            it.instrumentationScopeInfo.name == CorrelationIdWebFilter::class.java.name
        }

    /** Adds a synchronous processor with the in-memory exporter to Boot's SDK logger provider. */
    @TestConfiguration(proxyBeanMethods = false)
    class InMemoryLogs {
        @Bean
        fun inMemoryLogRecordProcessor(): LogRecordProcessor = SimpleLogRecordProcessor.create(exporter)
    }

    private companion object {
        val exporter: InMemoryLogRecordExporter = InMemoryLogRecordExporter.create()
        val log: org.slf4j.Logger = LoggerFactory.getLogger(OpenTelemetryLogsTest::class.java)
        val CORRELATION_ID: AttributeKey<String> = AttributeKey.stringKey(CorrelationIds.MDC_KEY)
        val ORIGINAL_CORRELATION_ID: AttributeKey<String> = AttributeKey.stringKey(CorrelationIds.ORIGINAL_LOG_KEY)
        val SERVICE_NAME: AttributeKey<String> = AttributeKey.stringKey("service.name")
    }
}
