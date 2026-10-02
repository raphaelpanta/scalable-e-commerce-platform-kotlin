package com.ecommerce.catalog.infrastructure

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

private const val CORRELATION_HEADER = "X-Correlation-Id"

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresContainerConfig::class)
@ExtendWith(OutputCaptureExtension::class)
class MetricsAndCorrelationIntegrationTest(
    @LocalServerPort private val port: Int,
) {
    private val client: WebTestClient by lazy {
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    @Test
    fun prometheusMetricsAreExposed() {
        val body =
            client
                .get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody

        body.shouldNotBeNull() shouldContain "jvm_memory_used_bytes"
    }

    @Test
    fun suppliedCorrelationIdIsEchoedAndLoggedAsOneJsonLine(output: CapturedOutput) {
        client
            .get()
            .uri("/actuator/health")
            .header(CORRELATION_HEADER, "abc-123")
            .exchange()
            .expectHeader()
            .valueEquals(CORRELATION_HEADER, "abc-123")

        val lines = output.out.lines().filter { it.contains("\"correlationId\":\"abc-123\"") }
        lines shouldHaveSize 1
        lines.single() shouldStartWith "{"
    }

    @Test
    fun missingCorrelationIdIsGeneratedAsUuid() {
        val generated =
            client
                .get()
                .uri("/actuator/health")
                .exchange()
                .returnResult(String::class.java)
                .responseHeaders
                .getFirst(CORRELATION_HEADER)

        UUID.fromString(generated.shouldNotBeNull()).toString() shouldBe generated
    }
}
