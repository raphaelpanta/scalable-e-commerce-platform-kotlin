package com.ecommerce.gateway

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort

/** Health groups and metrics live on the management port only; the public port does not route them. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class ManagementPortIT(
    @LocalServerPort private val port: Int,
    @LocalManagementPort private val managementPort: Int,
) {
    @Test
    fun `health, liveness, readiness and prometheus answer on the management port`() {
        managementPort shouldNotBe port
        val management = gatewayClient(managementPort)
        listOf("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").forEach { path ->
            management
                .get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .json("""{"status":"UP"}""")
        }
        management
            .get()
            .uri("/actuator/prometheus")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            .shouldNotBeNull() shouldContain "jvm_memory_used_bytes"
    }
}
