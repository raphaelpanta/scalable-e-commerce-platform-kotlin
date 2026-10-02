package com.ecommerce.platform.correlation

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ecommerce.platform.testapp.PlatformWebTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.web.server.LocalServerPort

private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

class CorrelationIdWebFilterTest(
    @LocalServerPort port: Int,
) : PlatformWebTest(port) {
    private val appender = ListAppender<ILoggingEvent>()
    private val accessLog = LoggerFactory.getLogger(CorrelationIdWebFilter::class.java) as Logger

    @BeforeEach
    fun capture() {
        appender.start()
        accessLog.addAppender(appender)
    }

    @AfterEach
    fun release() {
        accessLog.detachAppender(appender)
    }

    private fun correlation(header: String?): Pair<String?, Map<*, *>> {
        val result =
            client
                .get()
                .uri("/api/v1/public/correlation")
                .headers { headers -> header?.let { headers.set(CorrelationIds.HEADER, it) } }
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(Map::class.java)
                .returnResult()
        return result.responseHeaders.getFirst(CorrelationIds.HEADER) to result.responseBody.orEmpty()
    }

    @Test
    fun `a valid inbound id is echoed and visible to the handler, the MDC and the baggage`() {
        val id = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
        val (echoed, seen) = correlation(id)
        echoed shouldBe id
        seen shouldBe mapOf("current" to id, "attribute" to id, "header" to id, "mdc" to id, "baggage" to id)
        accessEvents()
            .single()
            .keyValuePairs
            .orEmpty()
            .shouldBeEmpty()
    }

    @Test
    fun `a missing id is generated`() {
        val (echoed, seen) = correlation(null)
        echoed.orEmpty() shouldMatch UUID_PATTERN
        seen["current"] shouldBe echoed
        seen["header"] shouldBe echoed
    }

    @Test
    fun `a malformed id is replaced and the original goes to the access log only`() {
        val (echoed, seen) = correlation("not valid; drop table")
        echoed.orEmpty() shouldMatch UUID_PATTERN
        seen["current"] shouldBe echoed
        val event = accessEvents().single()
        event.formattedMessage shouldBe "GET /api/v1/public/correlation 200"
        event.mdcPropertyMap[CorrelationIds.MDC_KEY] shouldBe echoed
        event.keyValuePairs.single().let {
            it.key shouldBe "originalCorrelationId"
            it.value shouldBe "not valid; drop table"
        }
    }

    @Test
    fun `error responses carry the id too`() {
        val response =
            client
                .get()
                .uri("/api/v1/me")
                .header(CorrelationIds.HEADER, "abc-123")
                .exchange()
                .expectStatus()
                .isUnauthorized
                .expectBody(Map::class.java)
                .returnResult()
        response.responseHeaders.getFirst(CorrelationIds.HEADER) shouldBe "abc-123"
        response.responseBody.orEmpty()["correlationId"] shouldBe "abc-123"
        accessEvents().single().formattedMessage shouldBe "GET /api/v1/me 401"
    }

    @Test
    fun `outside a request there is no correlation id`() {
        CorrelationIds.from(
            reactor.util.context.Context
                .empty(),
        ) shouldBe null
        CorrelationIds.from(
            reactor.util.context.Context
                .of(CorrelationIds.CONTEXT_KEY, "x"),
        ) shouldNotBe null
    }

    private fun accessEvents(): List<ILoggingEvent> = appender.list.filter { it.loggerName == accessLog.name }
}
